import type { FairValueTick, Trade } from "../api";
import { money } from "../format";

const W = 640;
const H = 220;
const PAD = { top: 14, right: 64, bottom: 18, left: 8 };

/** Fair value as a stepped line (it changes on events, not continuously) with trades as dots. */
export function PriceChart({ ticks, trades }: { ticks: FairValueTick[]; trades: Trade[] }) {
  const points = ticks.map((t) => ({ t: Date.parse(t.computedAt), v: t.fairValueCents }));
  const prints = trades.map((t) => ({ t: Date.parse(t.executedAt), v: t.priceCents }));
  const all = [...points, ...prints];
  if (all.length === 0) {
    return <div className="chart chart--empty">Prices appear here once the IPO opens.</div>;
  }

  const t0 = Math.min(...all.map((p) => p.t));
  const t1 = Math.max(...all.map((p) => p.t), t0 + 1);
  const lo = Math.min(...all.map((p) => p.v));
  const hi = Math.max(...all.map((p) => p.v));
  const pad = Math.max(10, (hi - lo) * 0.12);
  const v0 = Math.max(0, lo - pad);
  const v1 = hi + pad;

  const x = (t: number) => PAD.left + ((t - t0) / (t1 - t0)) * (W - PAD.left - PAD.right);
  const y = (v: number) => PAD.top + (1 - (v - v0) / (v1 - v0)) * (H - PAD.top - PAD.bottom);

  let path = "";
  points.forEach((p, i) => {
    path += i === 0 ? `M${x(p.t)},${y(p.v)}` : `H${x(p.t)}V${y(p.v)}`;
  });
  if (points.length > 0) path += `H${x(t1)}`;

  const latest = points.at(-1);
  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Fair value and trade prices over time">
        <line className="chart__grid" x1={PAD.left} x2={W - PAD.right} y1={y(hi)} y2={y(hi)} />
        <line className="chart__grid" x1={PAD.left} x2={W - PAD.right} y1={y(lo)} y2={y(lo)} />
        <text className="chart__axis" x={W - PAD.right + 8} y={y(hi) + 4}>{money(hi)}</text>
        <text className="chart__axis" x={W - PAD.right + 8} y={y(lo) + 4}>{money(lo)}</text>
        {path && <path className="chart__fair" d={path} />}
        {prints.map((p, i) => (
          <circle key={i} className="chart__trade" cx={x(p.t)} cy={y(p.v)} r={3.2} />
        ))}
        {latest && <circle className="chart__fair-head" cx={x(t1)} cy={y(latest.v)} r={4} />}
      </svg>
      <figcaption className="chart__legend">
        <span className="legend legend--fair">Fair value</span>
        <span className="legend legend--trade">Trades</span>
      </figcaption>
    </figure>
  );
}
