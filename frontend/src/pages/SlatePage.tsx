import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, type Market, type SlateDetail } from "../api";
import { GapMeter } from "../components/GapMeter";
import { gameClock, money } from "../format";
import { applySlateEvent } from "../liveState";
import { useStream } from "../stream";
import { PHASE } from "./phase";

function priceFor(market: Market) {
  return market.settlementValueCents ?? market.lastTradePriceCents ?? market.ipoClearingPriceCents;
}

export function SlatePage() {
  const { slateId } = useParams();
  const navigate = useNavigate();
  const [slate, setSlate] = useState<SlateDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (slateId) api.slate(slateId).then(setSlate).catch((e) => setError(e.message));
  }, [slateId]);
  useEffect(load, [load]);

  useStream(slateId ? `/topic/slates/${slateId}` : null, (event) => {
    if (event.type === "slate_transitioned") {
      load(); // IPO clearing prices and settlement values arrive with the phase change
      return;
    }
    setSlate((current) => (current ? applySlateEvent(current, event) : current));
  });

  // Sorted by the pre-game projection, which never changes: re-sorting on live prices would move
  // rows out from under the pointer several times a second.
  const markets = useMemo(
    () => [...(slate?.markets ?? [])].sort((a, b) => b.projectedFantasyPoints - a.projectedFantasyPoints),
    [slate],
  );

  if (error) return <p className="notice notice--error">Couldn’t load this slate: {error}</p>;
  if (!slate) return <p className="empty">Loading slate…</p>;

  const phase = PHASE[slate.status];
  const showFinal = slate.status === "SETTLED";

  return (
    <section className="page">
      <Link to="/" className="back">All slates</Link>
      <div className="slate-head">
        <h1 className="page__title">{slate.name}</h1>
        <span className="phase" data-phase={slate.status}>{phase.label}</span>
      </div>
      <p className="lede">{phase.hint}</p>

      <ol className="scoreboard" aria-label="Games">
        {slate.games.map((g) => (
          <li key={g.id} className="scoreboard__game" data-status={g.status}>
            <span className="scoreboard__teams">{g.awayTeamCode} at {g.homeTeamCode}</span>
            <span className="scoreboard__clock">{gameClock(g.status, g.period, g.clockSecondsRemaining, g.startsAt)}</span>
          </li>
        ))}
      </ol>

      <table className="markets">
        <caption className="sr-only">Athlete markets</caption>
        <thead>
          <tr>
            <th scope="col">Athlete</th>
            <th scope="col" className="num">Fair value</th>
            <th scope="col" className="num">{showFinal ? "Paid" : "Last"}</th>
            <th scope="col" className="markets__gap-col">{showFinal ? "" : "Market vs fair"}</th>
            <th scope="col"><span className="sr-only">Status</span></th>
          </tr>
        </thead>
        <tbody>
          {markets.map((m) => {
            const href = `/slates/${slate.id}/markets/${m.id}`;
            return (
              <tr key={m.id} className="markets__row" onClick={() => navigate(href)}>
                <th scope="row">
                  <Link to={href} className="athlete" onClick={(e) => e.stopPropagation()}>
                    <span className="athlete__name">{m.athleteName}</span>
                    <span className="athlete__meta">{m.teamCode}, {m.position}</span>
                  </Link>
                </th>
                <td className="num price price--fair">{money(m.fairValueCents ?? m.reservePriceCents)}</td>
                <td className="num price">{money(priceFor(m))}</td>
                <td className="markets__gap-col">
                  {showFinal ? null : <GapMeter last={m.lastTradePriceCents} fair={m.fairValueCents} />}
                </td>
                <td>{m.status === "HALTED" && <span className="badge badge--halted">Halted</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
