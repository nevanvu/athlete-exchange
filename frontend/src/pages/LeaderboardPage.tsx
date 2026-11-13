import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type LeaderboardRow, type SlateSummary } from "../api";
import { useAuth } from "../auth";
import { money, percent, signedMoney } from "../format";

export function LeaderboardPage() {
  const { session, me } = useAuth();
  const [slates, setSlates] = useState<SlateSummary[]>([]);
  const [scope, setScope] = useState<string>("overall");
  const [rows, setRows] = useState<LeaderboardRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.slates().then(setSlates).catch(() => setSlates([]));
  }, []);

  useEffect(() => {
    setRows(null);
    setError(null);
    (scope === "overall" ? api.leaderboard() : api.slateLeaderboard(scope))
      .then(setRows)
      .catch((e) => setError(e.message));
  }, [scope, session]);

  const overall = scope === "overall";

  return (
    <section className="page">
      <h1 className="page__title">Leaderboard</h1>
      <p className="lede">
        {overall
          ? "Ranked by account value: cash plus shares at their last price. Everyone starts with $10,000."
          : "Ranked by profit on this slate's athletes, with open shares at their last price."}
      </p>

      <label className="field field--inline">
        <span>Show</span>
        <select value={scope} onChange={(e) => setScope(e.target.value)}>
          <option value="overall">All time</option>
          {slates.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </label>

      {error && <p className="notice notice--error">Couldn’t load the leaderboard: {error}</p>}
      {rows?.length === 0 && <p className="empty">No one has traded {overall ? "yet" : "this slate yet"}.</p>}
      {rows && rows.length > 0 && (
        <table className="board">
          <thead>
            <tr>
              <th scope="col" className="num">Rank</th>
              <th scope="col">Trader</th>
              <th scope="col" className="num">{overall ? "Account value" : "Profit"}</th>
              {overall && <th scope="col" className="num">Return</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.rank}-${r.name}`} className={r.you ? "board__you" : undefined}>
                <td className="num board__rank">{r.rank}</td>
                <th scope="row">{r.name}{r.you && <span className="board__tag">You</span>}</th>
                <td className="num">{overall ? money(r.valueCents) : <Pnl cents={r.profitCents} />}</td>
                {overall && <td className="num"><Pnl cents={r.profitCents} label={percent(r.returnFraction)} /></td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="muted board__note">
        Traders are anonymous unless they choose to show their name.
        {session && me && !me.portfolioPublic && <> You’re shown as “Anonymous trader” to others. <Link to="/portfolio">Change this</Link>.</>}
      </p>
    </section>
  );
}

function Pnl({ cents, label }: { cents: number; label?: string }) {
  const direction = cents > 0 ? "up" : cents < 0 ? "down" : "flat";
  return <span className="pnl" data-direction={direction}>{label ?? signedMoney(cents)}</span>;
}
