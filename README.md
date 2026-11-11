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
- **Trading halts** — a market halts before any single-event fair-value move over 15% is published, so nobody trades against stale prices when an injury breaks.

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

Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · PostgreSQL + Flyway · Redis · Kafka (outbox pattern) · Gradle (Kotlin DSL, multi-module) · Testcontainers · Docker Compose · GitHub Actions · React frontend.

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

Requires Java 21+ and Docker.

```bash
./gradlew :app:bootRun   # starts postgres/redis/kafka via Docker Compose automatically
```

Run the tests (Testcontainers spins up its own Postgres):

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
