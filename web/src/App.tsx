import { useEffect, useState, type FormEvent } from "react";
import { QueryCache, QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { Car, LogOut, ScrollText } from "lucide-react";
import { Toaster } from "sonner";

import { api, Unauthorized } from "./api";
import { LogPage } from "./pages/Log";
import { TripsPage } from "./pages/Trips";

// La 401 (sesiune expirată) revenim la ecranul de login.
let onUnauthorized = () => {};

/**
 * Schimbă starea de login. Nu folosim queryClient.clear(): ar scoate și interogarea „me”
 * de sub componenta care o urmărește, iar ecranul ar rămâne blocat pe login.
 */
function setLoggedIn(user: boolean) {
  queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== "me" });
  queryClient.setQueryData(["me"], { user });
}
const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (e) => {
      if (e instanceof Unauthorized) onUnauthorized();
    },
  }),
  defaultOptions: { queries: { retry: (n, e) => !(e instanceof Unauthorized) && n < 2 } },
});

const TABS = [
  { path: "/", label: "Mașina", icon: Car },
  { path: "/jurnal", label: "Jurnal", icon: ScrollText },
] as const;

function usePath(): [string, (p: string) => void] {
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    const on = () => setPath(location.pathname);
    addEventListener("popstate", on);
    return () => removeEventListener("popstate", on);
  }, []);
  return [
    path,
    (p) => {
      history.pushState(null, "", p);
      setPath(p);
      scrollTo(0, 0);
    },
  ];
}

function Login({ onDone }: { onDone: () => void }) {
  const [user, setUser] = useState("");
  const [pass, setPass] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api.login(user, pass);
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={submit} className="mx-auto mt-24 max-w-sm space-y-3 rounded-2xl glass-card p-6">
      <div className="flex items-center gap-2">
        <Car className="h-6 w-6 text-sky-400" />
        <h1 className="text-xl font-semibold">FaikkitCar</h1>
      </div>
      <input
        autoComplete="username"
        placeholder="Utilizator"
        value={user}
        onChange={(e) => setUser(e.target.value)}
        className="w-full rounded-lg border border-border/40 bg-background/40 px-3 py-2"
      />
      <input
        type="password"
        autoComplete="current-password"
        placeholder="Parolă"
        value={pass}
        onChange={(e) => setPass(e.target.value)}
        className="w-full rounded-lg border border-border/40 bg-background/40 px-3 py-2"
      />
      {error && <p className="text-sm text-red-400">{error}</p>}
      <button
        disabled={busy || !user || !pass}
        className="w-full rounded-lg bg-sky-500 px-3 py-2 font-semibold text-slate-950 disabled:opacity-40"
      >
        Intră
      </button>
    </form>
  );
}

function Shell() {
  const me = useQuery({ queryKey: ["me"], queryFn: api.me, staleTime: Infinity });
  const [path, go] = usePath();
  onUnauthorized = () => setLoggedIn(false);

  if (me.isLoading) return null;
  if (!me.data?.user) {
    return (
      <Login
        onDone={() => setLoggedIn(true)}
      />
    );
  }
  const tab = TABS.find((t) => t.path === path) ?? TABS[0];
  return (
    <div className="mx-auto max-w-2xl space-y-4 px-4 pb-24 pt-4">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">FaikkitCar</h1>
          <p className="text-xs text-muted-foreground">Golf 6 · {tab.label}</p>
        </div>
        <button
          type="button"
          title="Ieși"
          onClick={async () => {
            await api.logout();
            setLoggedIn(false);
          }}
          className="rounded-lg p-2 text-muted-foreground hover:bg-white/5"
        >
          <LogOut className="h-5 w-5" />
        </button>
      </header>
      <nav className="flex gap-1 rounded-2xl glass-card p-1">
        {TABS.map((t) => (
          <button
            key={t.path}
            type="button"
            onClick={() => go(t.path)}
            className={`flex flex-1 items-center justify-center gap-2 rounded-xl py-2 text-sm ${
              t.path === tab.path ? "bg-sky-500/15 text-sky-400" : "text-muted-foreground"
            }`}
          >
            <t.icon className="h-4 w-4" />
            {t.label}
          </button>
        ))}
      </nav>
      <main className="space-y-4">{tab.path === "/jurnal" ? <LogPage /> : <TripsPage />}</main>
    </div>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Shell />
      <Toaster theme="dark" position="top-center" />
    </QueryClientProvider>
  );
}
