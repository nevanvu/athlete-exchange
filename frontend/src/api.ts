// Typed client for the Spring Boot API. Shapes mirror the controllers' response records.

export type SlateStatus = "SCHEDULED" | "IPO" | "OPEN_MARKET" | "CLOSED" | "SETTLED" | "VOIDED";
export type MarketStatus = "ACTIVE" | "HALTED";
export type GameStatus = "SCHEDULED" | "IN_PROGRESS" | "HALFTIME" | "FINAL" | "POSTPONED" | "CANCELLED";
export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET";
export type OrderStatus = "OPEN" | "PARTIALLY_FILLED" | "FILLED" | "CANCELLED";

export interface Level {
  priceCents: number;
  quantity: number;
}

export interface SlateSummary {
  id: string;
  name: string;
  status: SlateStatus;
  ipoOpensAt: string;
  ipoClosesAt: string;
}

export interface Game {
  id: string;
  homeTeamCode: string;
  awayTeamCode: string;
  status: GameStatus;
  period: number;
  clockSecondsRemaining: number;
  startsAt: string;
}

export interface Market {
  id: string;
  athleteId: string;
  athleteName: string;
  position: string;
  teamCode: string;
  gameId: string;
  status: MarketStatus;
  projectedFantasyPoints: number;
  projectedMinutes: number;
  reservePriceCents: number;
  totalShares: number;
  ipoClearingPriceCents: number | null;
  lastTradePriceCents: number | null;
  fairValueCents: number | null;
  settlementValueCents: number | null;
}

export interface SlateDetail extends SlateSummary {
  games: Game[];
  markets: Market[];
}

export interface Book {
  marketId: string;
  status: MarketStatus;
  haltReason: string | null;
  resumeAfter: string | null;
  lastTradePriceCents: number | null;
  ipoClearingPriceCents: number | null;
  fairValueCents: number | null;
  bids: Level[];
  asks: Level[];
}

export interface Trade {
  id: string;
  priceCents: number;
  quantity: number;
  executedAt: string;
}

export interface FairValueTick {
  fairValueCents: number;
  fpScored: number;
  fpRemaining: number;
  availability: number;
  newsFactor: number;
  gameFractionRemaining: number;
  asOfEventId: string | null;
  computedAt: string;
}

export interface Order {
  id: string;
  marketId: string;
  side: Side;
  type: OrderType;
  priceCents: number | null;
  quantity: number;
  filledQuantity: number;
  status: OrderStatus;
  escrowCents: number;
  createdAt: string;
}

export interface PlacedOrder {
  order: Order;
  trades: Trade[];
  /** The order stopped at your own resting order; the rest was cancelled. */
  selfTradePrevented: boolean;
}

export interface LeaderboardRow {
  rank: number;
  name: string;
  valueCents: number;
  profitCents: number;
  returnFraction: number;
  you: boolean;
}

export interface FeedItem {
  id: string;
  kind: "HALT" | "BIG_TRADE" | "IPO" | "PAYOUT";
  slateId: string | null;
  athleteMarketId: string | null;
  headline: string;
  detail: string | null;
  occurredAt: string;
}

export interface Me {
  username: string;
  portfolioPublic: boolean;
  admin: boolean;
}

export interface SurveillanceAlert {
  id: string;
  kind: "SELF_TRADE_BLOCKED" | "ROUND_TRIP";
  marketId: string;
  slateId: string;
  athlete: string | null;
  userA: string;
  userB: string;
  occurrences: number;
  detail: string;
  firstSeenAt: string;
  lastSeenAt: string;
  status: "OPEN" | "DISMISSED";
}

export interface IpoBid {
  id: string;
  marketId: string;
  maxPriceCents: number;
  quantity: number;
  allocatedQuantity: number;
  clearingPriceCents: number | null;
  status: "OPEN" | "ALLOCATED" | "REFUNDED" | "CANCELLED";
  createdAt: string;
}

export interface Position {
  marketId: string;
  slateId: string;
  athleteId: string;
  athleteName: string | null;
  quantity: number;
  lockedQuantity: number;
  averageCostCents: number;
  lastPriceCents: number | null;
  marketValueCents: number;
  unrealizedPnlCents: number;
  realizedPnlCents: number;
}

export interface Portfolio {
  cashCents: number;
  escrowCents: number;
  positionsValueCents: number;
  totalEquityCents: number;
  totalRealizedPnlCents: number;
  positions: Position[];
}

export interface Wallet {
  cashCents: number;
  escrowCents: number;
}

export interface AuthResponse {
  token: string;
  username: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

let authToken: string | null = null;

export function setAuthToken(token: string | null) {
  authToken = token;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (authToken) headers["Authorization"] = `Bearer ${authToken}`;

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let detail = response.statusText;
    try {
      const problem = await response.json();
      detail = problem.detail ?? problem.title ?? detail;
    } catch {
      // not a problem+json body
    }
    if (response.status === 401) detail = "Your session has expired. Sign in again.";
    throw new ApiError(response.status, detail);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export const api = {
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>("POST", "/api/auth/register", { username, email, password }),
  login: (username: string, password: string) =>
    request<AuthResponse>("POST", "/api/auth/login", { username, password }),

  wallet: () => request<Wallet>("GET", "/api/wallet"),
  slates: () => request<SlateSummary[]>("GET", "/api/slates"),
  slate: (id: string) => request<SlateDetail>("GET", `/api/slates/${id}`),
  book: (marketId: string) => request<Book>("GET", `/api/markets/${marketId}/book`),
  trades: (marketId: string) => request<Trade[]>("GET", `/api/markets/${marketId}/trades`),
  fairValue: (marketId: string) => request<FairValueTick[]>("GET", `/api/markets/${marketId}/fair-value`),

  orders: () => request<Order[]>("GET", "/api/orders"),
  placeOrder: (order: { marketId: string; side: Side; type: OrderType; priceCents: number | null; quantity: number }) =>
    request<PlacedOrder>("POST", "/api/orders", order),
  cancelOrder: (id: string) => request<Order>("DELETE", `/api/orders/${id}`),

  ipoBids: () => request<IpoBid[]>("GET", "/api/ipo/bids"),
  placeIpoBid: (marketId: string, maxPriceCents: number, quantity: number) =>
    request<IpoBid>("POST", "/api/ipo/bids", { marketId, maxPriceCents, quantity }),
  cancelIpoBid: (id: string) => request<IpoBid>("DELETE", `/api/ipo/bids/${id}`),

  portfolio: () => request<Portfolio>("GET", "/api/portfolio"),

  me: () => request<Me>("GET", "/api/me"),
  setPrivacy: (portfolioPublic: boolean) => request<Me>("PUT", "/api/me/privacy", { portfolioPublic }),
  leaderboard: () => request<LeaderboardRow[]>("GET", "/api/leaderboard"),
  slateLeaderboard: (slateId: string) => request<LeaderboardRow[]>("GET", `/api/slates/${slateId}/leaderboard`),
  feed: () => request<FeedItem[]>("GET", "/api/feed"),

  surveillance: (includeDismissed: boolean) =>
    request<SurveillanceAlert[]>("GET", `/api/admin/surveillance?includeDismissed=${includeDismissed}`),
  dismissAlert: (id: string) => request<SurveillanceAlert>("POST", `/api/admin/surveillance/${id}/dismiss`),
};
