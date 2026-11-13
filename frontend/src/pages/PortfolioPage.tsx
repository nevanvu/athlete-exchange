import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type Order, type Portfolio } from "../api";
import { useAuth } from "../auth";
import { money, quantity, signedMoney } from "../format";

function Pnl({ cents }: { cents: number }) {
  const direction = cents > 0 ? "up" : cents < 0 ? "down" : "flat";
  return <span className="pnl" data-direction={direction}>{signedMoney(cents)}</span>;
}

export function PortfolioPage() {
  const { fillVersion, refreshWallet } = useAuth();
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    Promise.all([api.portfolio(), api.orders()])
      .then(([p, o]) => {
        setPortfolio(p);
        setOrders(o.filter((order) => order.status === "OPEN" || order.status === "PARTIALLY_FILLED"));
      })
      .catch((e) => setError(e.message));
  }, []);
  useEffect(load, [load, fillVersion]);

  async function cancel(id: string) {
    try {
      await api.cancelOrder(id);
    } finally {
      load();
      refreshWallet();
    }
  }

  if (error) return <p className="notice notice--error">Couldn’t load your portfolio: {error}</p>;
  if (!portfolio) return <p className="empty">Loading portfolio…</p>;

  return (
    <section className="page">
      <h1 className="page__title">Portfolio</h1>
      <dl className="summary">
        <div><dt>Account value</dt><dd>{money(portfolio.totalEquityCents)}</dd></div>
        <div><dt>Cash</dt><dd>{money(portfolio.cashCents)}</dd></div>
        <div><dt>Held for open orders</dt><dd>{money(portfolio.escrowCents)}</dd></div>
        <div><dt>Realized profit</dt><dd><Pnl cents={portfolio.totalRealizedPnlCents} /></dd></div>
      </dl>

      <section className="panel">
        <h2>Holdings</h2>
        {portfolio.positions.length === 0 ? (
          <p className="muted">You don’t hold any shares. <Link to="/">Find a market</Link> to buy into.</p>
        ) : (
          <table className="holdings">
            <thead>
              <tr>
                <th scope="col">Athlete</th>
                <th scope="col" className="num">Shares</th>
                <th scope="col" className="num">Avg cost</th>
                <th scope="col" className="num">Last</th>
                <th scope="col" className="num">Value</th>
                <th scope="col" className="num">Unrealized</th>
              </tr>
            </thead>
            <tbody>
              {portfolio.positions.map((p) => (
                <tr key={p.marketId}>
                  <th scope="row">
                    <Link to={`/slates/${p.slateId}/markets/${p.marketId}`} className="athlete__name">
                      {p.athleteName ?? "Athlete"}
                    </Link>
                  </th>
                  <td className="num">{quantity(p.quantity)}{p.lockedQuantity > 0 && <span className="muted"> ({quantity(p.lockedQuantity)} listed)</span>}</td>
                  <td className="num">{money(p.averageCostCents)}</td>
                  <td className="num">{money(p.lastPriceCents)}</td>
                  <td className="num">{money(p.marketValueCents)}</td>
                  <td className="num"><Pnl cents={p.unrealizedPnlCents} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {orders.length > 0 && (
        <section className="panel">
          <h2>Open orders</h2>
          <ul className="my-orders">
            {orders.map((o) => (
              <li key={o.id}>
                <span>
                  {o.side === "BUY" ? "Buy" : "Sell"} {quantity(o.quantity - o.filledQuantity)} at {money(o.priceCents)}
                  {o.filledQuantity > 0 && <span className="muted"> ({quantity(o.filledQuantity)} filled)</span>}
                </span>
                <button className="link-button" onClick={() => cancel(o.id)}>Cancel</button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </section>
  );
}
