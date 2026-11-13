import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type Market, type Order, type SlateDetail } from "../api";
import { useAuth } from "../auth";
import { GapMeter } from "../components/GapMeter";
import { HaltBanner } from "../components/HaltBanner";
import { OrderBook } from "../components/OrderBook";
import { IpoBidForm, OrderTicket } from "../components/OrderTicket";
import { PriceChart } from "../components/PriceChart";
import { money, quantity } from "../format";
import { applyMarketEvent, type MarketView } from "../liveState";
import { useStream } from "../stream";
import { PHASE } from "./phase";

export function MarketPage() {
  const { slateId, marketId } = useParams();
  const { session, fillVersion } = useAuth();
  const [slate, setSlate] = useState<SlateDetail | null>(null);
  const [view, setView] = useState<MarketView | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!slateId || !marketId) return;
    Promise.all([api.slate(slateId), api.book(marketId), api.trades(marketId), api.fairValue(marketId)])
      .then(([s, book, trades, fairValueHistory]) => {
        setSlate(s);
        setView({ book, trades, fairValueHistory });
      })
      .catch((e) => setError(e.message));
  }, [slateId, marketId]);
  useEffect(load, [load]);

  const loadOrders = useCallback(() => {
    if (!session || !marketId) {
      setOrders([]);
      return;
    }
    api.orders()
      .then((all) => setOrders(all.filter((o) => o.marketId === marketId && (o.status === "OPEN" || o.status === "PARTIALLY_FILLED"))))
      .catch(() => setOrders([]));
  }, [session, marketId]);
  useEffect(loadOrders, [loadOrders, fillVersion]);

  useStream(marketId ? `/topic/markets/${marketId}` : null, (event) => {
    setView((current) => (current ? applyMarketEvent(current, event) : current));
  });
  useStream(slateId ? `/topic/slates/${slateId}` : null, (event) => {
    if (event.type === "slate_transitioned") load();
  });

  if (error) return <p className="notice notice--error">Couldn’t load this market: {error}</p>;
  if (!slate || !view || !marketId) return <p className="empty">Loading market…</p>;

  const market: Market | undefined = slate.markets.find((m) => m.id === marketId);
  if (!market) return <p className="empty">This market isn’t part of the slate. <Link to={`/slates/${slate.id}`}>Back to the slate</Link></p>;

  const { book } = view;
  const game = slate.games.find((g) => g.id === market.gameId);

  async function cancel(id: string) {
    try {
      await api.cancelOrder(id);
    } finally {
      loadOrders();
    }
  }

  return (
    <section className="page market-page">
      <Link to={`/slates/${slate.id}`} className="back">{slate.name}</Link>
      {book.status === "HALTED" && <HaltBanner reason={book.haltReason} resumeAfter={book.resumeAfter} />}

      <header className="market-head">
        <div>
          <h1 className="page__title">{market.athleteName}</h1>
          <p className="athlete__meta">
            {market.teamCode}, {market.position}
            {game ? `. ${game.awayTeamCode} at ${game.homeTeamCode}` : ""}. Projected {market.projectedFantasyPoints} fantasy points.
          </p>
        </div>
        <dl className="quote">
          <div className="quote__item quote__item--fair">
            <dt>Fair value</dt>
            <dd>{money(book.fairValueCents ?? market.reservePriceCents)}</dd>
          </div>
          <div className="quote__item">
            <dt>{slate.status === "SETTLED" ? "Paid out" : "Last trade"}</dt>
            <dd>{money(market.settlementValueCents ?? book.lastTradePriceCents ?? book.ipoClearingPriceCents)}</dd>
          </div>
          {slate.status !== "SETTLED" && (
            <div className="quote__item quote__item--gap">
              <dt>Market vs fair</dt>
              <dd><GapMeter last={book.lastTradePriceCents} fair={book.fairValueCents} /></dd>
            </div>
          )}
        </dl>
      </header>

      <div className="market-grid">
        <div className="market-grid__main">
          <section className="panel">
            <h2>Price</h2>
            <PriceChart ticks={view.fairValueHistory} trades={view.trades} />
          </section>
          <section className="panel">
            <h2>Recent trades</h2>
            {view.trades.length === 0 ? (
              <p className="muted">No trades yet.</p>
            ) : (
              <table className="tape">
                <thead>
                  <tr><th scope="col">Time</th><th scope="col" className="num">Price</th><th scope="col" className="num">Shares</th></tr>
                </thead>
                <tbody>
                  {view.trades.slice(0, 12).map((t) => (
                    <tr key={t.id}>
                      <td>{new Date(t.executedAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" })}</td>
                      <td className="num">{money(t.priceCents)}</td>
                      <td className="num">{quantity(t.quantity)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </div>

        <aside className="market-grid__side">
          {slate.status === "IPO" ? (
            <IpoBidForm marketId={market.id} reserveCents={market.reservePriceCents} onPlaced={load} />
          ) : (
            <OrderTicket marketId={market.id} book={book} slateStatus={slate.status} onPlaced={loadOrders} />
          )}
          <section className="panel">
            <h2>Order book</h2>
            {slate.status === "OPEN_MARKET" || book.bids.length + book.asks.length > 0 ? (
              <OrderBook bids={book.bids} asks={book.asks} fair={book.fairValueCents} />
            ) : (
              <p className="muted">{PHASE[slate.status].hint}</p>
            )}
          </section>
          {orders.length > 0 && (
            <section className="panel">
              <h2>Your open orders</h2>
              <ul className="my-orders">
                {orders.map((o) => (
                  <li key={o.id}>
                    <span>
                      {o.side === "BUY" ? "Buy" : "Sell"} {quantity(o.quantity - o.filledQuantity)} at {money(o.priceCents)}
                    </span>
                    <button className="link-button" onClick={() => cancel(o.id)}>Cancel</button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </aside>
      </div>
    </section>
  );
}
