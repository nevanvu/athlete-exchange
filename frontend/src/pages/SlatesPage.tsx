import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, type SlateSummary } from "../api";
import { PHASE } from "./phase";

export function SlatesPage() {
  const [slates, setSlates] = useState<SlateSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.slates().then(setSlates).catch((e) => setError(e.message));
  }, []);

  return (
    <section className="page">
      <h1 className="page__title">Tonight’s slates</h1>
      <p className="lede">
        Each athlete trades like a stock. Prices move with every basket and injury, and every share
        pays out its fantasy points × $0.25 when the games are final.
      </p>
      {error && <p className="notice notice--error">Couldn’t load slates: {error}</p>}
      {slates?.length === 0 && <p className="empty">No slates are scheduled yet.</p>}
      <ul className="slate-list">
        {slates?.map((slate) => (
          <li key={slate.id}>
            <Link to={`/slates/${slate.id}`} className="slate-link">
              <span className="slate-link__name">{slate.name}</span>
              <span className="phase" data-phase={slate.status}>{PHASE[slate.status].label}</span>
              <span className="slate-link__hint">{PHASE[slate.status].hint}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
