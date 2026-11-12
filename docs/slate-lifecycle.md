# Slate Lifecycle

A **slate** is a group of games (e.g. "NBA Tuesday Night") whose athletes trade together and settle together. Every athlete market belongs to exactly one slate, and the slate's state machine governs what every market in it may do.

## States

```
SCHEDULED → IPO → OPEN_MARKET → CLOSED → SETTLED
                                   ↘
                                 VOIDED
```

| State | Meaning | Trading allowed |
|---|---|---|
| `SCHEDULED` | Slate created, athletes listed, projections loading | None |
| `IPO` | Pre-game offering window; opening prices being set | IPO bids only |
| `OPEN_MARKET` | Games live (or about to start); continuous trading | Limit + market orders, cancels |
| `CLOSED` | All games final; awaiting official stats | None (cancels of resting orders auto-executed) |
| `SETTLED` | Payouts applied to wallets | None, terminal |
| `VOIDED` | Slate abandoned (mass postponement, data failure) | None, terminal |

### Market-level overlay: `HALTED`

Halts are **per athlete market**, not a slate state. A market in an `OPEN_MARKET` slate can be `ACTIVE` or `HALTED`. Halting cancels nothing; it rejects new orders and matches until the halt lifts. This keeps one injured athlete from freezing the whole slate.

## Transitions

| Transition | Trigger | Guards |
|---|---|---|
| `SCHEDULED → IPO` | Scheduler, at `ipo_opens_at` | Projections present for every listed athlete |
| `IPO → OPEN_MARKET` | Scheduler, at `ipo_closes_at` | IPO clearing prices computed; allocations and refunds posted to ledger |
| `OPEN_MARKET → CLOSED` | Last `game_end` event in the slate | Every game in slate has status `FINAL` |
| `CLOSED → SETTLED` | Settlement job | Official (non-provisional) stats for all athletes; settlement run committed atomically |
| `ACTIVE → HALTED` (market) | `injury_event` severity ≥ `OUT`, a single-event FV move > 15% and ≥ $1.00, or manual flag | Slate is `OPEN_MARKET` |
| `HALTED → ACTIVE` (market) | Fair value recomputed and published after the triggering event | Minimum halt duration elapsed (e.g. 30s) |
| `any non-terminal → VOIDED` | Manual admin action, or watchdog (no stats N hours after close) | — |

All transitions are recorded in a `slate_transitions` audit table (slate_id, from, to, trigger, occurred_at). Transitions are idempotent: re-delivering the trigger event is a no-op if the slate is already past that state.

## Phase behavior

### IPO
- Fixed number of shares per athlete (e.g. 1,000) offered via a single-price auction: users submit (quantity, max price) bids with funds escrowed; at close, the clearing price is the highest price that sells all shares (or the reserve price derived from projections if undersubscribed).
- Unfilled bids and the difference between bid price and clearing price are released from escrow in the same ledger transaction that allocates shares.
- Unsold shares go to the house account, which the market-maker bot may later sell into the open market.

### Open market
- Limit and market orders, partial fills, cancels — handled by the matching engine (price-time priority, one book per athlete market).
- Buys escrow `price × quantity` of cash; sells escrow shares. Fills move escrowed amounts; cancels release them.
- Live events reprice the **reference fair value** (see `fair-value-model.md`); the order book moves on its own, but the UI shows both.

### Close
- On the slate's last `game_end`: reject new orders, cancel all resting orders, release all escrow. The books must be empty before settlement runs.

### Settlement
Settlement is one idempotent job per slate, keyed by `(slate_id, settlement_run_id)`:

1. Freeze: snapshot every position (user, athlete, quantity) at `CLOSED`.
2. Compute: `settlement_value(athlete) = clamp(fantasy_points × POINT_VALUE, FLOOR, CAP)` from official stats (constants in `fair-value-model.md`).
3. Pay: one ledger batch — debit the slate's settlement pool account, credit each holder `quantity × settlement_value`, retire the shares. The batch either fully commits or fully rolls back.
4. Mark `SETTLED` in the same transaction as the ledger batch.

Re-running the job after a crash finds the committed `settlement_run_id` and exits.

### Corrections
Stat providers revise scores after the fact. Within a **correction window** (e.g. 48h after settlement):

- A `stat_correction` event recomputes `settlement_value`. The delta is applied as a new ledger batch of adjustment entries (positive or negative), keyed by `(slate_id, correction_id)` for idempotency — never by mutating the original settlement entries.
- A negative adjustment may push a wallet negative; that's acceptable in paper money and surfaces in reconciliation reports.

After the window closes, corrections are logged but not applied.

### Voiding
`VOIDED` cancels all orders, releases all escrow, and refunds IPO purchases at the IPO clearing price in one ledger batch. Positions acquired in the open market are refunded at the user's average cost (simplest defensible rule; document it in the UI).

## Invariants (test these)

1. Total cash across all wallets + escrow + house accounts is constant except at settlement, where the change equals exactly `Σ shares × settlement_value − Σ IPO proceeds` against the settlement pool.
2. A share count per athlete never changes outside IPO allocation and settlement retirement.
3. No order fill in any state except `OPEN_MARKET` with the market `ACTIVE`.
4. Settlement and correction batches are idempotent under redelivery and crash-restart (property-test with random kill points).
