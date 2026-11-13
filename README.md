# Athlete Exchange

A paper-money athlete stock exchange. Each athlete is a tradable ticker: users buy and sell shares before and during games, prices move with supply and demand anchored to live performance, and shares settle at the end of each slate based on fantasy points.

> **Disclaimer**: virtual currency only — no deposits, no withdrawals, no prizes of monetary value. Athlete names are used as plain text with no headshots, logos, or league branding. This is a portfolio / learning project, not a gambling or fantasy-sports product.

## Why this design

Sports stock markets whose prices depended only on demand (Football Index) collapsed; the ones that worked (Jock MKT-style) tied every share to a fundamental: settlement against fantasy points. Here, every share settles at `clamp(fantasy_points × $0.25, $1, $100)`, so prices have a floor, a ceiling, and a reason to move when something happens on the court.

## Core concepts

- **Slate** — a group of games that trade and settle together. Lifecycle: `SCHEDULED → IPO → OPEN_MARKET → CLOSED → SETTLED | VOIDED`, with per-market `HALTED` overlays. See [docs/slate-lifecycle.md](docs/slate-lifecycle.md).
- **IPO** — pre-game single-price auction sets opening prices from projections.
- **Open market** — limit/market orders matched by a price-time-priority engine, one book per athlete.
- **Fair value** — a model-based reference price updated live from game events, shown beside the market price. News and rumors nudge it with decay but never change settlement. See [docs/fair-value-model.md](docs/fair-value-model.md).
- **Settlement** — atomic, idempotent end-of-slate payout through a double-entry ledger, with a 48-hour correction window for revised stats.
- **Trading halts** — a market halts before any single-event fair-value move over 15% (and at least $1) or an OUT ruling is published, so nobody trades against stale prices when an injury breaks.
- **Market maker** — the house quotes both sides around fair value from its own inventory, widening with volatility and going quiet during halts.

## Architecture

```
                 ┌────────────────────────┐
  simulator /    │  Ingestion & Scoring   │   fair_value_updated,
  replay files ─▶│  (events → fantasy pts │──▶ market_halted ─────┐
                 │   → fair value)        │                       │
                 └──────────┬─────────────┘                       ▼
                            │ game-events (Kafka)        ┌────────────────┐
                            ▼                            │  WebSocket     │
┌─────────────┐   ┌────────────────────┐   outbox ──────▶│  fan-out       │──▶ React UI
│  API (REST  │──▶│  Market service    │                 │  (live ticker) │    (ticker,
│  + JWT)     │   │  slates, IPO,      │                 └────────────────┘    portfolio,
└─────────────┘   │  settlement        │                                       charts)
                  └───────┬────────────┘
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
  ┌──────────────────┐        ┌──────────────────┐
  │ Matching engine  │        │ Ledger & wallets │
  │ (pure library,   │        │ (double-entry,   │
  │  one book/market)│        │  escrow)         │
  └──────────────────┘        └──────────────────┘
            PostgreSQL (Flyway) · Redis (hot prices, books)
```

Event contracts for everything above: [docs/event-schema.md](docs/event-schema.md).

## Stack

Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · PostgreSQL + Flyway · Redis · Kafka (outbox pattern) · Gradle (Kotlin DSL, multi-module) · Testcontainers · Docker Compose · GitHub Actions · React 19 + Vite + TypeScript frontend, STOMP over WebSocket.

## Build order

1. Users, auth, wallets, double-entry ledger with escrow
2. Athletes, games, slates — lifecycle state machine
3. Matching engine as a pure library (property-based tests: price-time priority, conservation)
4. Orders with escrow; IPO auction; open-market trading
5. Game simulator + event ingestion (idempotent consumers)
6. Scoring engine, fair value model, atomic settlement
7. Injury/news events and trading halts
8. Live price streaming (WebSocket) + frontend ticker
9. Leaderboards, position limits, wash-trading detection
10. CI/CD and deployment

## Testing strategy

- **Property-based**: matching engine invariants (no negative balances, fills conserve cash and shares, price-time priority holds under random order streams).
- **Replay-based**: recorded event files replayed through the pipeline must reproduce identical fair-value sequences and settlement values.
- **Concurrency**: parallel order submission against one book; crash-restart of the settlement job at random points proves idempotency.
- **Integration**: Testcontainers for Postgres, Redis, Kafka.

## Getting started

Requires Java 21+, Docker, and Node 20+ for the frontend.

### Run it live

Four terminals: the exchange (with a two-minute IPO), the game simulator, the frontend, and the demo driver.

```bash
./gradlew :app:bootRun --args='--athletex.demo-seed.ipo-opens-in=20s --athletex.demo-seed.ipo-duration=120s --athletex.scheduler.fixed-delay-ms=2000'
```

```bash
./gradlew :simulator:bootRun
```

```bash
cd frontend && npm install && npm run dev
```

Open http://localhost:5173, create an account (it starts with $10,000 of paper money) and bid in the IPO. Then start the games; the script waits for the IPO to close:

```bash
python3 scripts/live-demo.py start --speed 20
```

Prices move with every basket. Injure an athlete to watch the market halt, reprice and reopen 30 seconds later:

```bash
python3 scripts/live-demo.py injure "Avery Stone"
```

When both games are final the slate settles on its own and the portfolio shows realized profit. To start over, reset the database with `docker compose down -v`.

The browser gets everything over one WebSocket (STOMP at `/ws`): `/topic/slates/{id}` carries the ticker, game clocks and phase changes, `/topic/markets/{id}` the order book, trades, fair value and halts, and `/user/queue/fills` the signed-in user's own fills. Market data is public; trading needs the JWT from sign-in.

### Backend only

```bash
./gradlew :app:bootRun   # starts postgres/redis/kafka via Docker Compose automatically
```

Run the tests (Testcontainers spins up its own Postgres and Kafka):

```bash
./gradlew build
```

### Scripted demo

Start the server with a short IPO window, then run the walkthrough client (standard-library Python). It registers three paper-money traders, bids in the demo slate's IPO, waits for the auction to clear, trades on the open market, and prints the resulting portfolios and P&L.

```bash
./gradlew :app:bootRun --args='--athletex.demo-seed.ipo-opens-in=25s --athletex.demo-seed.ipo-duration=80s --athletex.scheduler.fixed-delay-ms=2000'
```

```bash
python3 scripts/demo.py
```

To replay it, reset the database first with `docker compose down -v`.

### Game simulator

The `simulator` module is a separate Spring Boot app (port 8081) that plays NBA games and streams the events in `docs/event-schema.md` to the `game-events` Kafka topic. The exchange consumes that topic, de-duplicates by event id, reorders by per-game sequence, and applies stat lines, game clock, injuries and news. When a slate's last game goes FINAL, the slate closes and resting orders are cancelled.

```bash
./gradlew :simulator:bootRun
```

Start a game for a slate (`gameId`, `slateId` and athlete ids come from `GET /api/slates/{id}`). `speed` 60 plays a 48-minute game in 48 seconds, and a fixed `seed` replays the same game:

```bash
curl -s localhost:8081/simulations -H 'Content-Type: application/json' -d '{"gameId":"<game-uuid>","slateId":"<slate-uuid>","speed":60,"seed":7,"athletes":[{"athleteId":"<athlete-uuid>","projectedFantasyPoints":42,"projectedMinutes":34}]}'
```

Inject an injury or a news item mid-game:

```bash
curl -s localhost:8081/simulations/<game-uuid>/injury -H 'Content-Type: application/json' -d '{"athleteId":"<athlete-uuid>","status":"OUT","detail":"ankle"}'
```

Set `athletex.simulator.record-dir` to also write each game's events as NDJSON for replay.

### Fair value and settlement

Every applied game event reprices the affected markets' reference **fair value**: points already scored, plus the projection scaled by the share of the game left and the athlete's injury status, times a decaying news factor. Fair value appears next to the market price in `GET /api/slates/{id}` and `GET /api/markets/{id}/book`, and its history (with model inputs) is at `GET /api/markets/{id}/fair-value`.

A few seconds after a slate closes, the settlement job pays every holder `clamp(fantasy points x $0.25, $1, $100)` per share from the settlement pool in one ledger transaction, retires the shares and marks the slate `SETTLED`. A stat revision within 48 hours pays or claws back the difference against the holdings at settlement. Results and corrections: `GET /api/slates/{id}/settlement`.

`./gradlew :simulator:recordGame --args="<file> <seed>"` writes a sample game to NDJSON; `app/src/test/resources/replays/sample-game.ndjson` is replayed by the test suite.
