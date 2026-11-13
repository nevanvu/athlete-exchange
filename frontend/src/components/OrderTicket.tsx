import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { api, type Book, type OrderType, type Side, type SlateStatus } from "../api";
import { useAuth } from "../auth";
import { money, quantity as qty } from "../format";

function toCents(dollars: string): number | null {
  const value = Number(dollars);
  return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : null;
}

/** Buy or sell shares on the open market. */
export function OrderTicket({ marketId, book, slateStatus, onPlaced }: {
  marketId: string;
  book: Book;
  slateStatus: SlateStatus;
  onPlaced: () => void;
}) {
  const { session, refreshWallet } = useAuth();
  const [side, setSide] = useState<Side>("BUY");
  const [type, setType] = useState<OrderType>("LIMIT");
  const [price, setPrice] = useState(() => ((book.fairValueCents ?? 100) / 100).toFixed(2));
  const [shares, setShares] = useState("10");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  if (!session) {
    return (
      <section className="panel ticket">
        <h2>Trade</h2>
        <p className="muted"><Link to="/sign-in">Sign in</Link> to trade. Every account starts with $10,000 of paper money.</p>
      </section>
    );
  }

  const closedReason =
    slateStatus !== "OPEN_MARKET"
      ? slateStatus === "IPO" || slateStatus === "SCHEDULED"
        ? "Trading opens when the IPO closes."
        : "Trading has ended for this slate."
      : book.status === "HALTED"
        ? "Trading is halted. Orders reopen when the halt lifts."
        : null;

  const priceCents = type === "LIMIT" ? toCents(price) : null;
  const count = Math.floor(Number(shares));
  const valid = count > 0 && (type === "MARKET" || priceCents != null);
  const verb = side === "BUY" ? "Buy" : "Sell";

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || closedReason) return;
    setBusy(true);
    setMessage(null);
    try {
      const placed = await api.placeOrder({ marketId, side, type, priceCents, quantity: count });
      const filled = placed.order.filledQuantity;
      const text =
        filled === 0
          ? `Order placed: ${verb.toLowerCase()} ${qty(count)} at ${money(priceCents)}. It rests on the book until matched.`
          : filled === count
            ? `Filled: ${verb === "Buy" ? "bought" : "sold"} ${qty(filled)} shares.`
            : placed.order.status === "CANCELLED"
              ? `Partly filled: ${qty(filled)} of ${qty(count)}. No more shares were available, so the rest was cancelled.`
              : `Partly filled: ${qty(filled)} of ${qty(count)}. The rest rests on the book.`;
      setMessage({ kind: "ok", text });
      refreshWallet();
      onPlaced();
    } catch (error) {
      setMessage({ kind: "error", text: (error as Error).message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel ticket">
      <h2>Trade</h2>
      <form onSubmit={submit}>
        <div className="segmented" role="radiogroup" aria-label="Side">
          {(["BUY", "SELL"] as Side[]).map((s) => (
            <button key={s} type="button" role="radio" aria-checked={side === s}
              className={`segmented__option segmented__option--${s.toLowerCase()}`} onClick={() => setSide(s)}>
              {s === "BUY" ? "Buy" : "Sell"}
            </button>
          ))}
        </div>
        <div className="segmented segmented--quiet" role="radiogroup" aria-label="Order type">
          {(["LIMIT", "MARKET"] as OrderType[]).map((t) => (
            <button key={t} type="button" role="radio" aria-checked={type === t}
              className="segmented__option" onClick={() => setType(t)}>
              {t === "LIMIT" ? "At my price" : "At best price"}
            </button>
          ))}
        </div>
        {type === "LIMIT" && (
          <label className="field">
            <span>Price per share</span>
            <input inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
          </label>
        )}
        <label className="field">
          <span>Shares</span>
          <input inputMode="numeric" value={shares} onChange={(e) => setShares(e.target.value)} />
        </label>
        <p className="ticket__estimate">
          {type === "LIMIT" && priceCents != null && count > 0
            ? `${side === "BUY" ? "Up to" : "At least"} ${money(priceCents * count)}`
            : type === "MARKET"
              ? "Fills against the book now; any unfilled shares are cancelled."
              : " "}
        </p>
        <button className={`button button--${side.toLowerCase()}`} disabled={!valid || busy || closedReason != null}>
          {busy ? "Sending…" : `${verb} ${count > 0 ? qty(count) : ""} shares`}
        </button>
        {closedReason && <p className="muted">{closedReason}</p>}
        {message && <p className={`notice notice--${message.kind}`} role="status">{message.text}</p>}
      </form>
    </section>
  );
}

/** Bid in the pre-game single-price auction. */
export function IpoBidForm({ marketId, reserveCents, onPlaced }: {
  marketId: string;
  reserveCents: number;
  onPlaced: () => void;
}) {
  const { session, refreshWallet } = useAuth();
  const [price, setPrice] = useState(() => (reserveCents / 100).toFixed(2));
  const [shares, setShares] = useState("50");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  if (!session) {
    return (
      <section className="panel ticket">
        <h2>IPO</h2>
        <p className="muted"><Link to="/sign-in">Sign in</Link> to bid in the IPO.</p>
      </section>
    );
  }

  const maxCents = toCents(price);
  const count = Math.floor(Number(shares));

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (maxCents == null || count <= 0) return;
    setBusy(true);
    setMessage(null);
    try {
      await api.placeIpoBid(marketId, maxCents, count);
      setMessage({ kind: "ok", text: `Bid placed. ${money(maxCents * count)} is held until the auction clears.` });
      refreshWallet();
      onPlaced();
    } catch (error) {
      setMessage({ kind: "error", text: (error as Error).message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel ticket">
      <h2>IPO</h2>
      <p className="muted">
        Every winning bid pays the same clearing price, never more than your maximum. Reserve is {money(reserveCents)}.
      </p>
      <form onSubmit={submit}>
        <label className="field">
          <span>Most you would pay per share</span>
          <input inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
        </label>
        <label className="field">
          <span>Shares</span>
          <input inputMode="numeric" value={shares} onChange={(e) => setShares(e.target.value)} />
        </label>
        <button className="button button--buy" disabled={busy || maxCents == null || count <= 0}>
          {busy ? "Sending…" : "Place bid"}
        </button>
        {message && <p className={`notice notice--${message.kind}`} role="status">{message.text}</p>}
      </form>
    </section>
  );
}
