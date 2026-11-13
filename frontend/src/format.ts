const dollars = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** 1234 -> "$12.34"; null -> "—". Paper money, always shown in dollars and cents. */
export function money(cents: number | null | undefined): string {
  return cents == null ? "—" : dollars.format(cents / 100);
}

/** Signed amount for P&L: "+$4.20", "−$1.05" (true minus sign), "$0.00". */
export function signedMoney(cents: number): string {
  if (cents === 0) return money(0);
  return (cents > 0 ? "+" : "−") + money(Math.abs(cents));
}

/** Relative difference of a price from a reference, e.g. last trade vs fair value. */
export function premium(priceCents: number | null, referenceCents: number | null): number | null {
  if (priceCents == null || referenceCents == null || referenceCents === 0) return null;
  return (priceCents - referenceCents) / referenceCents;
}

export function percent(fraction: number | null, digits = 1): string {
  if (fraction == null) return "—";
  const value = (fraction * 100).toFixed(digits);
  if (Number(value) === 0) return `${(0).toFixed(digits)}%`;
  return (fraction > 0 ? "+" : "−") + value.replace("-", "") + "%";
}

export function quantity(n: number): string {
  return n.toLocaleString("en-US");
}

/** Game clock: "Q3 4:05", "Half", "Final", "Tip-off 7:30 PM". */
export function gameClock(status: string, period: number, secondsRemaining: number, startsAt?: string): string {
  switch (status) {
    case "FINAL":
      return "Final";
    case "HALFTIME":
      return "Half";
    case "POSTPONED":
      return "Postponed";
    case "CANCELLED":
      return "Cancelled";
    case "IN_PROGRESS": {
      const m = Math.floor(secondsRemaining / 60);
      const s = String(secondsRemaining % 60).padStart(2, "0");
      return `Q${Math.max(1, period)} ${m}:${s}`;
    }
    default:
      return startsAt
        ? `Tip-off ${new Date(startsAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}`
        : "Scheduled";
  }
}

export function countdown(untilIso: string | null, now: number): string {
  if (!untilIso) return "";
  const seconds = Math.max(0, Math.ceil((Date.parse(untilIso) - now) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
