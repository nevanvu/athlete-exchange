import { useEffect, useState } from "react";
import { countdown } from "../format";

/** Referee stripes: trading is stopped while a big event is priced in. */
export function HaltBanner({ reason, resumeAfter }: { reason: string | null; resumeAfter: string | null }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, []);
  const remaining = countdown(resumeAfter, now);

  return (
    <div className="halt" role="status">
      <div className="halt__stripes" aria-hidden="true" />
      <div className="halt__body">
        <strong>Trading halted</strong>
        <span>{reason ? reason.charAt(0).toUpperCase() + reason.slice(1) : "Paused by the exchange"}.</span>
        <span className="halt__clock">
          {resumeAfter ? (remaining === "0:00" ? "Reopening now" : `Reopens in ${remaining}`) : "Reopens when the exchange lifts it"}
        </span>
      </div>
    </div>
  );
}
