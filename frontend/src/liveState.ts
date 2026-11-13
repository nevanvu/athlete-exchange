// Pure reducers that apply streamed market events to state loaded over REST.
// The page loads a snapshot first, then folds every message in arrival order; Kafka keeps
// a market's messages in order and STOMP preserves order per subscription.

import type { Book, FairValueTick, Game, Level, Market, MarketStatus, SlateDetail, SlateStatus, Trade } from "./api";

export type StreamEvent =
  | { type: "fair_value_updated"; athleteMarketId: string; fairValueCents: number; computedAt: string;
      asOfEventId: string | null;
      components: { fpScored: number; fpRemaining: number; availability: number; newsFactor: number; gameFractionRemaining: number } }
  | { type: "trade_executed"; athleteMarketId: string; tradeId: string; priceCents: number; quantity: number;
      takerSide: "BUY" | "SELL"; executedAt: string; side?: "BUY" | "SELL" }
  | { type: "book_updated"; athleteMarketId: string; bids: Level[]; asks: Level[]; lastTradePriceCents: number | null }
  | { type: "market_halted"; athleteMarketId: string; reason: string; resumeAfter: string | null }
  | { type: "market_resumed"; athleteMarketId: string }
  | { type: "slate_transitioned"; slateId: string; to: SlateStatus }
  | { type: "game_updated"; gameId: string; status: Game["status"]; period: number; clockSecondsRemaining: number };

function updateMarket(markets: Market[], id: string, change: Partial<Market>): Market[] {
  let changed = false;
  const next = markets.map((m) => {
    if (m.id !== id) return m;
    changed = true;
    return { ...m, ...change };
  });
  return changed ? next : markets;
}

/** Slate page: the ticker of every market plus the games' scoreboard. */
export function applySlateEvent(slate: SlateDetail, event: StreamEvent): SlateDetail {
  switch (event.type) {
    case "fair_value_updated":
      return { ...slate, markets: updateMarket(slate.markets, event.athleteMarketId, { fairValueCents: event.fairValueCents }) };
    case "trade_executed":
      return { ...slate, markets: updateMarket(slate.markets, event.athleteMarketId, { lastTradePriceCents: event.priceCents }) };
    case "market_halted":
      return { ...slate, markets: updateMarket(slate.markets, event.athleteMarketId, { status: "HALTED" as MarketStatus }) };
    case "market_resumed":
      return { ...slate, markets: updateMarket(slate.markets, event.athleteMarketId, { status: "ACTIVE" as MarketStatus }) };
    case "slate_transitioned":
      return { ...slate, status: event.to };
    case "game_updated":
      return {
        ...slate,
        games: slate.games.map((g) =>
          g.id === event.gameId
            ? { ...g, status: event.status, period: event.period, clockSecondsRemaining: event.clockSecondsRemaining }
            : g,
        ),
      };
    default:
      return slate;
  }
}

export interface MarketView {
  book: Book;
  trades: Trade[]; // newest first
  fairValueHistory: FairValueTick[]; // oldest first
}

const MAX_TRADES = 50;
const MAX_TICKS = 400;

/** Market page: depth, tape, fair-value history and halt state for one market. */
export function applyMarketEvent(view: MarketView, event: StreamEvent): MarketView {
  switch (event.type) {
    case "book_updated":
      return {
        ...view,
        book: { ...view.book, bids: event.bids, asks: event.asks, lastTradePriceCents: event.lastTradePriceCents ?? view.book.lastTradePriceCents },
      };
    case "trade_executed": {
      if (view.trades.some((t) => t.id === event.tradeId)) return view;
      const trade: Trade = { id: event.tradeId, priceCents: event.priceCents, quantity: event.quantity, executedAt: event.executedAt };
      return {
        ...view,
        book: { ...view.book, lastTradePriceCents: event.priceCents },
        trades: [trade, ...view.trades].slice(0, MAX_TRADES),
      };
    }
    case "fair_value_updated": {
      const tick: FairValueTick = {
        fairValueCents: event.fairValueCents,
        ...event.components,
        asOfEventId: event.asOfEventId,
        computedAt: event.computedAt,
      };
      return {
        ...view,
        book: { ...view.book, fairValueCents: event.fairValueCents },
        fairValueHistory: [...view.fairValueHistory, tick].slice(-MAX_TICKS),
      };
    }
    case "market_halted":
      return { ...view, book: { ...view.book, status: "HALTED", haltReason: event.reason, resumeAfter: event.resumeAfter } };
    case "market_resumed":
      return { ...view, book: { ...view.book, status: "ACTIVE", haltReason: null, resumeAfter: null } };
    default:
      return view;
  }
}
