# Fair Value Model

Every athlete market shows two numbers: the **market price** (last trade / book midpoint, set by supply and demand) and the **reference fair value** (model-based estimate of what a share will settle for). The gap between them is what users trade on. Fair value never forces a trade; it anchors the display, drives the market-maker bot's quotes, and triggers halts.

## Settlement formula (the anchor)

```
settlement_value = clamp(fantasy_points_final × POINT_VALUE, FLOOR, CAP)

POINT_VALUE = $0.25 per fantasy point
FLOOR      = $1.00
CAP        = $100.00
```

Published, fixed per sport, and never changed mid-slate. The floor gives every share an underlying value (the lesson from Football Index: price must tie to real outcomes, not demand alone). The cap bounds the house's settlement exposure.

Fantasy points come from the scoring engine, e.g. basketball: `pts×1 + reb×1.2 + ast×1.5 + stl×3 + blk×3 − tov×1`.

## Fair value at time t

```
FV(t) = clamp(E[fp_final | t] × POINT_VALUE, FLOOR, CAP) × news_factor(t)
```

(The clamp-of-expectation is an approximation of the expectation-of-clamp; fine for display and quoting, and worth a code comment.)

### Expected final fantasy points

```
E[fp_final | t] = fp_scored(t) + fp_remaining(t)

fp_remaining(t) = projection_rate × minutes_remaining_est(t) × availability(t)
```

- **`projection_rate`** — pre-game projected fantasy points divided by projected minutes. Pre-game, `fp_scored = 0` and `fp_remaining` = the full projection, so FV during the IPO is just the projection run through the settlement formula. This also sets the IPO reserve price.
- **`minutes_remaining_est(t)`** — projected minutes × fraction of the game remaining, from game-clock events. Blowout adjustment optional (v2).
- **`availability(t)`** — multiplier from the latest injury status:

| Status | availability |
|---|---|
| `ACTIVE` | 1.0 |
| `QUESTIONABLE` | 0.6 |
| `DOUBTFUL` | 0.25 |
| `OUT` | 0.0 |

An injury event therefore produces exactly the behavior we want: `fp_scored` is locked in, `fp_remaining` collapses, FV drops sharply — and the market halts while the new FV is computed and published (see `slate-lifecycle.md`).

### News factor (sentiment, non-settling)

Trade rumors and news nudge sentiment but never change settlement. Each news event `i` carries an `impact` in [−0.15, +0.15] and decays exponentially:

```
news_factor(t) = clamp(1 + Σᵢ impactᵢ × exp(−(t − tᵢ)/τ), 0.7, 1.3)

τ = 30 minutes (half-life ≈ 21 min)
```

Bounded so stale or spammy news can't dominate the fundamental anchor. Expired terms (contribution < 0.001) are dropped from the sum.

## Update cadence and publication

- Recompute FV for an athlete whenever a `stat_line`, `injury_event`, `news_event`, or game-clock event arrives for them — target: event received → FV published in under 2 seconds.
- Also recompute on a 60s timer during live play so news sentiment decays between events. (Game-clock movement already arrives as `game_status` events.)
- Persist the latest FV on the market row and every change as a `fair_value_ticks` row (with the model inputs), and publish a `fair_value_updated` message (athlete_id, slate_id, fv, previous fv, components breakdown, as_of_event_id) to `market-events` through the transactional outbox. The WebSocket layer (M8) fans it out; a Redis cache for hot reads can sit in front of the market row then.
- News decay is evaluated at the triggering event's own timestamp during ingestion (wall-clock time only on the timer), so replaying a recorded game reproduces the same FV sequence.
- Expected points go through the same rounding as settlement (`Pricing.settlementValueCents`), so a finished game's FV equals exactly what settlement pays.
- `as_of_event_id` makes updates traceable and lets the replay harness assert FV determinism: same event stream in, same FV sequence out.

## Halt rule

If a single event moves FV by more than **15% and by at least $1.00**, or rules the athlete **OUT**, halt the market before publishing the new FV, publish, hold the halt for a minimum of 30 seconds, then reopen. This is the stale-quote protection: nobody trades against a pre-injury price in the gap before the reprice lands.

- The $1.00 minimum keeps cheap athletes tradable: a $3.00 bench player would otherwise halt on every three-pointer (+$0.75 is a 25% move).
- Halts apply only while the slate is `OPEN_MARKET`; pre-game news reprices the IPO without halting it.
- The halt is written in the same transaction as the repricing, and the outbox relays in insertion order, so `market_halted` always reaches consumers before the `fair_value_updated` that caused it.
- Order placement and repricing both take the market's row lock, so an order racing a halt either fills strictly before it or is rejected; it can never fill against the stale price after the halt is committed.
- A second shock during a halt extends it. Manual halts never lift automatically. Halt history: `GET /api/markets/{id}/halts`.

## Market-maker bot (liquidity)

The house bot quotes around FV in thin markets:

- Bid at `FV × (1 − spread)`, ask at `FV × (1 + spread)`, spread `min(5%, 2% + 2 × volatility)`, where volatility is the root-mean-square of the last 10 relative FV changes.
- Trades the house account's own inventory (unsold IPO shares) and cash ($250,000 seeded from the house ledger account) through the normal order path, 50 shares a side.
- Inventory-limited: no bid once holdings would exceed the cap (2,000 shares), no ask without shares (no shorting).
- Re-quotes every 2 seconds when its desired quote changed; pulls its quotes while a market is halted, and the halt resumer pulls them again before reopening so nothing priced before the halt is left on the book.
- The house is paid at settlement like any other holder.

## What's deliberately out (v1)

Per-athlete variance models, correlation between teammates, blowout/garbage-time minutes models, and Bayesian in-game projection updates. The structure above leaves slots for all of them (`projection_rate` and `minutes_remaining_est` are the extension points), which is a good thing to say in an interview.
