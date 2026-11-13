import { useEffect, useState } from "react";
import { api, type FeedItem } from "../api";
import { applyFeedEvent } from "../liveState";
import { useStream } from "../stream";

const LABEL: Record<FeedItem["kind"], string> = {
  HALT: "Halt",
  BIG_TRADE: "Big trade",
  IPO: "IPO",
  PAYOUT: "Payout",
};

function timeAgo(iso: string, now: number): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  return new Date(iso).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

/** Notable moves across every slate, live. */
export function FeedPanel() {
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    api.feed().then(setItems).catch(() => setItems([]));
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  useStream("/topic/feed", (event) => {
    setItems((current) => applyFeedEvent(current ?? [], event));
    setNow(Date.now());
  });

  return (
    <section className="panel feed" aria-live="polite">
      <h2>Notable moves</h2>
      {items === null ? (
        <p className="muted">Loading…</p>
      ) : items.length === 0 ? (
        <p className="muted">Halts, big trades, IPO results and payouts show up here as they happen.</p>
      ) : (
        <ol className="feed__list">
          {items.map((item) => (
            <li key={item.id} className="feed__item" data-kind={item.kind}>
              <span className="feed__kind">{LABEL[item.kind]}</span>
              <p className="feed__headline">{item.headline}</p>
              {item.detail && <p className="feed__detail">{item.detail}</p>}
              <time className="feed__time" dateTime={item.occurredAt}>{timeAgo(item.occurredAt, now)}</time>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
