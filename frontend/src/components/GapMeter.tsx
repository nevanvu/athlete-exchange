import { percent, premium } from "../format";

const RANGE = 0.1; // the track spans fair value ±10%

/**
 * Where the last trade sits against fair value: the tick is fair value, the dot is the market.
 * The gap between them is what traders act on, so it gets the most visual weight in a row.
 */
export function GapMeter({ last, fair }: { last: number | null; fair: number | null }) {
  const gap = premium(last, fair);
  if (gap == null) {
    return <span className="gap gap--empty">No trades yet</span>;
  }
  const position = Math.max(-1, Math.min(1, gap / RANGE));
  const direction = gap > 0.0005 ? "rich" : gap < -0.0005 ? "cheap" : "even";
  const words =
    direction === "rich" ? "above fair value" : direction === "cheap" ? "below fair value" : "at fair value";
  return (
    <span className="gap" data-direction={direction} role="img" aria-label={`Last trade ${percent(gap)} ${words}`}>
      <span className="gap__track" aria-hidden="true">
        <span className="gap__fair" />
        <span className="gap__dot" style={{ left: `${50 + position * 50}%` }} />
      </span>
      <span className="gap__label" aria-hidden="true">
        {direction === "rich" ? "▲" : direction === "cheap" ? "▼" : "•"} {percent(gap)}
      </span>
    </span>
  );
}
