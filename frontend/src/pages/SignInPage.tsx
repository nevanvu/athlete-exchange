import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth";

export function SignInPage() {
  const { signIn, register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<"sign-in" | "register">("sign-in");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "sign-in") await signIn(username, password);
      else await register(username, email, password);
      navigate("/");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page page--narrow">
      <h1 className="page__title">{mode === "sign-in" ? "Sign in" : "Create an account"}</h1>
      <p className="lede">
        {mode === "sign-in"
          ? "Trade athletes with paper money."
          : "New accounts start with $10,000 of paper money. It has no cash value."}
      </p>
      <form className="panel form" onSubmit={submit}>
        <label className="field">
          <span>Username</span>
          <input autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
        </label>
        {mode === "register" && (
          <label className="field">
            <span>Email</span>
            <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
        )}
        <label className="field">
          <span>Password</span>
          <input type="password" autoComplete={mode === "sign-in" ? "current-password" : "new-password"}
            minLength={mode === "register" ? 8 : undefined} value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button className="button button--buy" disabled={busy}>
          {busy ? "One moment…" : mode === "sign-in" ? "Sign in" : "Create account"}
        </button>
        {error && <p className="notice notice--error" role="alert">{error}</p>}
      </form>
      <p className="muted">
        {mode === "sign-in" ? "New here? " : "Already have an account? "}
        <button className="link-button" onClick={() => { setMode(mode === "sign-in" ? "register" : "sign-in"); setError(null); }}>
          {mode === "sign-in" ? "Create an account" : "Sign in"}
        </button>
      </p>
    </section>
  );
}
