import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type SurveillanceAlert } from "../api";
import { useAuth } from "../auth";

const KIND: Record<SurveillanceAlert["kind"], { title: string; explain: string }> = {
  ROUND_TRIP: {
    title: "Round-trip trading",
    explain: "Two accounts trading the same athlete back and forth while little changes hands net.",
  },
  SELF_TRADE_BLOCKED: {
    title: "Self-trade blocked",
    explain: "An account’s order reached its own resting order and was stopped by the exchange.",
  },
};

/** Operators only. Shows real usernames: this is where wash trading is reviewed. */
export function SurveillancePage() {
  const { me } = useAuth();
  const [includeDismissed, setIncludeDismissed] = useState(false);
  const [alerts, setAlerts] = useState<SurveillanceAlert[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.surveillance(includeDismissed).then(setAlerts).catch((e) => setError(e.message));
  }, [includeDismissed]);
  useEffect(load, [load]);

  if (me && !me.admin) {
    return <p className="empty">This page is for exchange operators.</p>;
  }

  async function dismiss(id: string) {
    await api.dismissAlert(id);
    load();
  }

  return (
    <section className="page">
      <h1 className="page__title">Surveillance</h1>
      <p className="lede">
        Possible wash trading, newest activity first. Dismiss an alert once it’s reviewed; it reopens if the
        pattern continues.
      </p>
      <label className="check">
        <input type="checkbox" checked={includeDismissed} onChange={(e) => setIncludeDismissed(e.target.checked)} />
        Show dismissed alerts
      </label>
      {error && <p className="notice notice--error">Couldn’t load alerts: {error}</p>}
      {alerts?.length === 0 && <p className="empty">No open alerts.</p>}
      <ul className="alerts">
        {alerts?.map((a) => (
          <li key={a.id} className="panel alert" data-status={a.status}>
            <div className="alert__head">
              <h2>{KIND[a.kind].title}</h2>
              <Link to={`/slates/${a.slateId}/markets/${a.marketId}`}>{a.athlete ?? "Market"}</Link>
            </div>
            <p className="alert__who">
              {a.kind === "SELF_TRADE_BLOCKED" ? a.userA : `${a.userA} and ${a.userB}`}
            </p>
            <p>{a.detail}</p>
            <p className="muted">
              {KIND[a.kind].explain} Last seen {new Date(a.lastSeenAt).toLocaleString()}.
            </p>
            {a.status === "OPEN" ? (
              <button className="button button--small" onClick={() => dismiss(a.id)}>Dismiss</button>
            ) : (
              <p className="muted">Dismissed</p>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
