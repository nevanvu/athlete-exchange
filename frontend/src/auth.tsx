import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, setAuthToken, type Me, type Wallet } from "./api";
import { setStreamToken, useStream } from "./stream";

interface Session {
  token: string;
  username: string;
}

interface AuthState {
  session: Session | null;
  wallet: Wallet | null;
  me: Me | null;
  setMe: (me: Me) => void;
  signIn: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  signOut: () => void;
  refreshWallet: () => void;
  /** Bumps whenever one of the user's orders fills, so views can refetch. */
  fillVersion: number;
}

const STORAGE_KEY = "athletex.session";
const AuthContext = createContext<AuthState | null>(null);

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

function saveSession(session: Session | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // storage unavailable: the session lasts for this page only
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    const restored = loadSession();
    setAuthToken(restored?.token ?? null);
    return restored;
  });
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [fillVersion, setFillVersion] = useState(0);

  useEffect(() => {
    setAuthToken(session?.token ?? null);
    setStreamToken(session?.token ?? null);
  }, [session]);

  const refreshWallet = useCallback(() => {
    if (!session) {
      setWallet(null);
      return;
    }
    api.wallet().then(setWallet).catch((error) => {
      if (error.status === 401) setSession(null);
    });
  }, [session]);

  useEffect(refreshWallet, [refreshWallet]);

  useEffect(() => {
    if (!session) {
      setMe(null);
      return;
    }
    api.me().then(setMe).catch(() => setMe(null));
  }, [session]);

  useStream(session ? "/user/queue/fills" : null, () => {
    setFillVersion((v) => v + 1);
    refreshWallet();
  });

  const start = useCallback((next: Session) => {
    saveSession(next);
    setAuthToken(next.token);
    setSession(next);
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      session,
      wallet,
      me,
      setMe,
      fillVersion,
      refreshWallet,
      signIn: async (username, password) => start(await api.login(username, password)),
      register: async (username, email, password) => start(await api.register(username, email, password)),
      signOut: () => {
        saveSession(null);
        setAuthToken(null);
        setSession(null);
      },
    }),
    [session, wallet, me, fillVersion, refreshWallet, start],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error("useAuth outside AuthProvider");
  return auth;
}
