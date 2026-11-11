# Event Schema

All game data enters the system as events on a single ingestion topic (`game-events`), whether they come from the simulator, a replay of a recorded game, or (later) a real provider behind the same interface. Downstream, the scoring engine emits derived events (`fair_value_updated`, `market_halted`, …) on separate topics.

## Envelope

Every event shares this envelope; `payload` varies by `type`.

```json
{
  "event_id": "evt_01JCZX8K4R9QW2M5T7N3VB6YH8",
  "type": "stat_line",
  "schema_version": 1,
  "source": "simulator",
  "game_id": "gm_20261111_BOS_NYK",
  "slate_id": "slate_nba_2026-11-11",
  "sequence": 482,
  "occurred_at": "2026-11-11T01:14:22.310Z",
  "ingested_at": "2026-11-11T01:14:22.845Z",
  "corrects": null,
  "payload": { }
}
```

| Field | Notes |
|---|---|
| `event_id` | ULID, globally unique. **Idempotency key**: consumers record processed ids and skip duplicates. |
| `source` | `simulator`, `replay`, or a provider name. |
| `sequence` | Monotonic per `game_id` from the source. Detects gaps and out-of-order delivery; consumers buffer briefly and reorder, or process out of order where the handler is commutative. |
| `occurred_at` vs `ingested_at` | Game time vs pipeline time; the difference is the ingestion-latency metric. |
| `corrects` | `event_id` of an earlier event this one revises, else null. A correction replaces the earlier payload in full. |

Kafka partition key: `game_id` (preserves per-game ordering). Topics: `game-events` (ingestion), `price-updates` (FV + market data fan-out), `notifications`.

## Event types

### Game lifecycle — `game_status`
```json
{ "status": "IN_PROGRESS", "period": 2, "clock_seconds_remaining": 341 }
```
`status`: `SCHEDULED | IN_PROGRESS | HALFTIME | FINAL | POSTPONED | CANCELLED`. Clock updates drive `minutes_remaining_est` in the fair value model. `FINAL` on the last game closes the slate; `POSTPONED`/`CANCELLED` may void it.

### Stat update — `stat_line`
Cumulative stat line per athlete (not deltas — cumulative lines make corrections and idempotent reprocessing trivial, since applying the same line twice is a no-op):
```json
{
  "athlete_id": "ath_jtatum",
  "stats": { "pts": 18, "reb": 5, "ast": 4, "stl": 1, "blk": 0, "tov": 2 },
  "minutes_played": 19.5,
  "is_final": false
}
```
The scoring engine converts `stats` to fantasy points on every update. `is_final: true` lines (one per athlete after `FINAL`) are what settlement reads; settlement will not run on provisional lines.

### Injury — `injury_event`
```json
{
  "athlete_id": "ath_jtatum",
  "status": "OUT",
  "detail": "ankle, will not return",
  "returning_this_game": false
}
```
`status`: `ACTIVE | QUESTIONABLE | DOUBTFUL | OUT` → the `availability` multiplier. Severity ≥ `OUT` (and any FV move > 15%) triggers a market halt before the reprice is published.

### News / rumor — `news_event`
```json
{
  "athlete_id": "ath_jtatum",
  "headline": "Trade rumor: discussions reported",
  "impact": -0.08,
  "confidence": 0.4
}
```
Feeds only the decaying `news_factor` — never settlement. `impact` ∈ [−0.15, 0.15]; effective impact = `impact × confidence`.

### Correction — any type with `corrects` set
A `stat_line` with `corrects` and `is_final: true` arriving after settlement triggers the correction flow in `slate-lifecycle.md` (adjustment ledger batch inside the 48h window).

## Derived events (published, not ingested)

- `fair_value_updated` — `{ athlete_id, slate_id, fv, components: { fp_scored, fp_remaining, availability, news_factor }, as_of_event_id }`
- `market_halted` / `market_resumed` — `{ athlete_market_id, reason, triggered_by_event_id }`
- `trade_executed`, `order_book_delta` — from the matching engine, for tickers and charts.

Carrying `as_of_event_id` / `triggered_by_event_id` through makes every price explainable and every replay assertable.

## Consumer rules

1. **Idempotency**: dedupe on `event_id` (processed-events table or Redis set with TTL past the retention window) before any side effect.
2. **Ordering**: per-game order comes from the partition key; if `sequence` gaps, hold a small reorder buffer (e.g. 2s) before processing ahead.
3. **Corrections**: always full-replacement semantics — recompute from the corrected cumulative line, never patch increments.
4. **Replay**: the simulator and the replay harness emit the identical schema, so a recorded event file replayed through the pipeline must reproduce the same FV sequence and the same settlement values. This is the backbone of the integration test suite.

## Simulator responsibilities

The game simulator generates plausible `game_status` + `stat_line` streams (per-athlete scoring rates from their projections, randomized within variance), and exposes demo controls to inject an `injury_event` or `news_event` on demand — which is how halts and FV crashes get demonstrated live.
