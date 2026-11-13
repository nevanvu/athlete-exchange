import type { SlateStatus } from "../api";

/** What each slate phase means to a trader. */
export const PHASE: Record<SlateStatus, { label: string; hint: string }> = {
  SCHEDULED: { label: "Opens soon", hint: "The IPO hasn’t started yet." },
  IPO: { label: "IPO open", hint: "Bid for shares before tip-off. Everyone pays the same clearing price." },
  OPEN_MARKET: { label: "Trading live", hint: "Buy and sell while the games are played." },
  CLOSED: { label: "Games final", hint: "Trading has ended. Shares pay out once the box scores are official." },
  SETTLED: { label: "Paid out", hint: "Every share paid its fantasy points × $0.25 (between $1 and $100)." },
  VOIDED: { label: "Voided", hint: "This slate was cancelled and refunded." },
};
