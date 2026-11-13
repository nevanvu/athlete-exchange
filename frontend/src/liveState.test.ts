import { describe, expect, it } from "vitest";
import type { Book, Market, SlateDetail } from "./api";
import { percent, premium, signedMoney, gameClock } from "./format";
import { applyMarketEvent, applySlateEvent, type MarketView } from "./liveState";

const market = (id: string): Market => ({
  id, athleteId: `a-${id}`, athleteName: `Athlete ${id}`, position: "PG", teamCode: "HRB", gameId: "g1",
  status: "ACTIVE", projectedFantasyPoints: 40, projectedMinutes: 32, reservePriceCents: 1000, totalShares: 1000,
  ipoClearingPriceCents: 1000, lastTradePriceCents: 1000, fairValueCents: 1000, settlementValueCents: null,
});

const slate: SlateDetail = {
  id: "s1", name: "Opening Night", status: "OPEN_MARKET", ipoOpensAt: "", ipoClosesAt: "",
  games: [{ id: "g1", homeTeamCode: "HRB", awayTeamCode: "IRN", status: "IN_PROGRESS", period: 1, clockSecondsRemaining: 720, startsAt: "" }],
  markets: [market("m1"), market("m2")],
};

const book: Book = {
  marketId: "m1", status: "ACTIVE", haltReason: null, resumeAfter: null, lastTradePriceCents: 1000,
  ipoClearingPriceCents: 1000, fairValueCents: 1000, bids: [], asks: [],
};

const view: MarketView = { book, trades: [], fairValueHistory: [] };

describe("applySlateEvent", () => {
  it("updates only the market a fair value change belongs to", () => {
    const next = applySlateEvent(slate, {
      type: "fair_value_updated", athleteMarketId: "m2", fairValueCents: 1250, computedAt: "", asOfEventId: "e1",
      components: { fpScored: 10, fpRemaining: 40, availability: 1, newsFactor: 1, gameFractionRemaining: 1 },
    });
    expect(next.markets.find((m) => m.id === "m2")?.fairValueCents).toBe(1250);
    expect(next.markets.find((m) => m.id === "m1")).toBe(slate.markets[0]);
  });

  it("tracks halts, trades, the game clock and the phase", () => {
    let next = applySlateEvent(slate, { type: "market_halted", athleteMarketId: "m1", reason: "injury", resumeAfter: null });
    expect(next.markets[0].status).toBe("HALTED");
    next = applySlateEvent(next, { type: "market_resumed", athleteMarketId: "m1" });
    expect(next.markets[0].status).toBe("ACTIVE");
    next = applySlateEvent(next, {
      type: "trade_executed", athleteMarketId: "m1", tradeId: "t1", priceCents: 1080, quantity: 5, takerSide: "BUY", executedAt: "",
    });
    expect(next.markets[0].lastTradePriceCents).toBe(1080);
    next = applySlateEvent(next, { type: "game_updated", gameId: "g1", status: "HALFTIME", period: 2, clockSecondsRemaining: 0 });
    expect(next.games[0].status).toBe("HALFTIME");
    next = applySlateEvent(next, { type: "slate_transitioned", slateId: "s1", to: "CLOSED" });
    expect(next.status).toBe("CLOSED");
  });
});

describe("applyMarketEvent", () => {
  it("replaces depth from a book update and keeps the last price when none is sent", () => {
    const next = applyMarketEvent(view, {
      type: "book_updated", athleteMarketId: "m1", lastTradePriceCents: null,
      bids: [{ priceCents: 980, quantity: 50 }], asks: [{ priceCents: 1020, quantity: 50 }],
    });
    expect(next.book.bids).toEqual([{ priceCents: 980, quantity: 50 }]);
    expect(next.book.lastTradePriceCents).toBe(1000);
  });

  it("puts new trades first and ignores a duplicate delivery", () => {
    const trade = { type: "trade_executed" as const, athleteMarketId: "m1", tradeId: "t1", priceCents: 1020, quantity: 50, takerSide: "BUY" as const, executedAt: "2026-11-12T20:00:00Z" };
    const once = applyMarketEvent(view, trade);
    const twice = applyMarketEvent(once, trade);
    expect(once.trades).toHaveLength(1);
    expect(twice).toBe(once);
    expect(once.book.lastTradePriceCents).toBe(1020);
  });

  it("shows and clears a halt", () => {
    const halted = applyMarketEvent(view, {
      type: "market_halted", athleteMarketId: "m1", reason: "injury: athlete ruled OUT", resumeAfter: "2026-11-12T20:00:30Z",
    });
    expect(halted.book.status).toBe("HALTED");
    expect(halted.book.haltReason).toContain("OUT");
    const resumed = applyMarketEvent(halted, { type: "market_resumed", athleteMarketId: "m1" });
    expect(resumed.book).toMatchObject({ status: "ACTIVE", haltReason: null, resumeAfter: null });
  });

  it("appends fair value history", () => {
    const next = applyMarketEvent(view, {
      type: "fair_value_updated", athleteMarketId: "m1", fairValueCents: 300, computedAt: "2026-11-12T20:00:00Z", asOfEventId: "e9",
      components: { fpScored: 12, fpRemaining: 0, availability: 0, newsFactor: 1, gameFractionRemaining: 0.5 },
    });
    expect(next.book.fairValueCents).toBe(300);
    expect(next.fairValueHistory.at(-1)).toMatchObject({ fairValueCents: 300, availability: 0 });
  });
});

describe("format", () => {
  it("formats premiums and signed money with true minus signs", () => {
    expect(percent(premium(1100, 1000))).toBe("+10.0%");
    expect(percent(premium(900, 1000))).toBe("−10.0%");
    expect(percent(premium(1000, 1000))).toBe("0.0%");
    expect(premium(null, 1000)).toBeNull();
    expect(signedMoney(-105)).toBe("−$1.05");
    expect(signedMoney(420)).toBe("+$4.20");
  });

  it("reads like a scoreboard", () => {
    expect(gameClock("IN_PROGRESS", 3, 245)).toBe("Q3 4:05");
    expect(gameClock("HALFTIME", 2, 0)).toBe("Half");
    expect(gameClock("FINAL", 4, 0)).toBe("Final");
  });
});

describe("applyFeedEvent", () => {
  it("adds streamed items newest first, once", async () => {
    const { applyFeedEvent } = await import("./liveState");
    const event = {
      type: "feed_item" as const, id: "f1", kind: "HALT" as const, slateId: "s1", athleteMarketId: "m1",
      headline: "Avery Stone halted: injury: athlete ruled OUT.", detail: null, occurredAt: "2026-11-12T20:00:00Z",
    };
    const once = applyFeedEvent([], event);
    expect(once).toHaveLength(1);
    expect(once[0]).not.toHaveProperty("type");
    expect(applyFeedEvent(once, event)).toBe(once);
  });
});
