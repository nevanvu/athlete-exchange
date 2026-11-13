import type { Level } from "../api";
import { money, quantity } from "../format";

const ROWS = 8;

function LevelRow({ level, side, max }: { level: Level; side: "bid" | "ask"; max: number }) {
  return (
    <tr className={`book__row book__row--${side}`}>
      <td className="num">{money(level.priceCents)}</td>
      <td className="num book__size">
        <span className="book__depth" style={{ width: `${(level.quantity / max) * 100}%` }} aria-hidden="true" />
        {quantity(level.quantity)}
      </td>
    </tr>
  );
}

/** Price ladder: asks above (best ask nearest the middle), bids below, fair value in the gap. */
export function OrderBook({ bids, asks, fair }: { bids: Level[]; asks: Level[]; fair: number | null }) {
  const shownAsks = asks.slice(0, ROWS).reverse();
  const shownBids = bids.slice(0, ROWS);
  const max = Math.max(1, ...shownAsks.map((l) => l.quantity), ...shownBids.map((l) => l.quantity));
  const spread = asks[0] && bids[0] ? asks[0].priceCents - bids[0].priceCents : null;

  return (
    <table className="book">
      <caption className="sr-only">Order book</caption>
      <thead>
        <tr>
          <th scope="col" className="num">Price</th>
          <th scope="col" className="num">Shares</th>
        </tr>
      </thead>
      <tbody>
        {shownAsks.length === 0 && (
          <tr className="book__empty"><td colSpan={2}>No sellers</td></tr>
        )}
        {shownAsks.map((l) => <LevelRow key={`a${l.priceCents}`} level={l} side="ask" max={max} />)}
        <tr className="book__mid">
          <td colSpan={2}>
            <span>{spread == null ? "No spread" : `Spread ${money(spread)}`}</span>
            <span className="book__fair">Fair {money(fair)}</span>
          </td>
        </tr>
        {shownBids.map((l) => <LevelRow key={`b${l.priceCents}`} level={l} side="bid" max={max} />)}
        {shownBids.length === 0 && (
          <tr className="book__empty"><td colSpan={2}>No buyers</td></tr>
        )}
      </tbody>
    </table>
  );
}
