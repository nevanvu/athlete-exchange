import { Link, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth";
import { money } from "./format";
import { useStreamStatus } from "./stream";
import { MarketPage } from "./pages/MarketPage";
import { PortfolioPage } from "./pages/PortfolioPage";
import { SignInPage } from "./pages/SignInPage";
import { SlatePage } from "./pages/SlatePage";
import { SlatesPage } from "./pages/SlatesPage";

function LiveIndicator() {
  const status = useStreamStatus();
  const label = status === "live" ? "Live" : status === "connecting" ? "Connecting" : "Reconnecting";
  return (
    <span className="live" data-status={status} title="Real-time price feed">
      <span className="live__dot" aria-hidden="true" />
      {label}
    </span>
  );
}

export function App() {
  const { session, wallet, signOut } = useAuth();
  return (
    <div className="app">
      <a className="skip" href="#main">Skip to content</a>
      <header className="topbar">
        <Link to="/" className="wordmark">Athlete Exchange</Link>
        <nav className="topbar__nav" aria-label="Main">
          <NavLink to="/" end>Markets</NavLink>
          <NavLink to="/portfolio">Portfolio</NavLink>
        </nav>
        <div className="topbar__account">
          <LiveIndicator />
          {session ? (
            <>
              <span className="topbar__cash" title="Cash available to trade">{money(wallet?.cashCents)}</span>
              <span className="topbar__user">{session.username}</span>
              <button className="link-button" onClick={signOut}>Sign out</button>
            </>
          ) : (
            <Link to="/sign-in" className="button button--small">Sign in</Link>
          )}
        </div>
      </header>

      <main id="main" className="main">
        <Routes>
          <Route path="/" element={<SlatesPage />} />
          <Route path="/slates/:slateId" element={<SlatePage />} />
          <Route path="/slates/:slateId/markets/:marketId" element={<MarketPage />} />
          <Route path="/portfolio" element={session ? <PortfolioPage /> : <Navigate to="/sign-in" replace />} />
          <Route path="/sign-in" element={<SignInPage />} />
          <Route path="*" element={<p className="empty">That page doesn’t exist. <Link to="/">Back to markets</Link></p>} />
        </Routes>
      </main>

      <footer className="footer">
        Paper money only: virtual currency with no cash value, no deposits, no withdrawals, no prizes.
        Athletes and teams are fictional.
      </footer>
    </div>
  );
}
