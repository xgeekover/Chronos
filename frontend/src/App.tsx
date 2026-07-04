import {
  QueryClient,
  QueryClientProvider,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { lazy, type ReactNode, Suspense, useEffect, useState } from "react";
import { type Auth, api, canOperate, clearAuth, getAuth } from "./api/client";
import { Login } from "./features/auth/Login";
import { DebugPanel } from "./features/debug/DebugPanel";
import { Logs } from "./features/logs/Logs";
import { TagsTree } from "./features/tags/TagsTree";
import {
  Badge,
  Button,
  ErrorNote,
  Loading,
  Panel,
  qualityTone,
  StatCard,
} from "./ui/kit";
import { Toaster } from "./ui/toast";

// Code-split the heavy views (React Flow, ECharts) so the initial bundle stays small.
const TimeMachine = lazy(() =>
  import("./features/timemachine/TimeMachine").then((m) => ({
    default: m.TimeMachine,
  })),
);
const Flows = lazy(() =>
  import("./features/flows/Flows").then((m) => ({ default: m.Flows })),
);

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchInterval: 5000 } },
});

type View = "dashboard" | "flows" | "tags" | "timemachine" | "logs";

// minimal inline icons (no icon dependency)
function Icon({ path }: { path: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      className="h-4 w-4"
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d={path} />
    </svg>
  );
}
const ICONS: Record<View, string> = {
  dashboard: "M4 13h6V4H4zM14 20h6V4h-6zM4 20h6v-5H4z",
  flows: "M5 6h4v4H5zM15 14h4v4h-4zM9 8h3a3 3 0 0 1 3 3v3M9 8h6M15 16H9",
  tags: "M3 7h7l4 4-7 7-8-8zM7 7h.01",
  timemachine: "M12 8v4l3 2M21 12a9 9 0 1 1-3-6.7L21 7",
  logs: "M4 6h16M4 12h16M4 18h10",
};

function Dashboard() {
  const qc = useQueryClient();
  const summary = useQuery({ queryKey: ["summary"], queryFn: api.dashboard });
  const devices = useQuery({ queryKey: ["devices"], queryFn: api.listDevices });
  const nodes = useQuery({ queryKey: ["nodes"], queryFn: api.listNodes });
  const tasks = useQuery({ queryKey: ["tasks"], queryFn: api.listTasks });
  const values = useQuery({ queryKey: ["values"], queryFn: api.currentValues });
  const run = useMutation({
    mutationFn: api.runTask,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["values"] });
      qc.invalidateQueries({ queryKey: ["summary"] });
    },
  });

  const s = summary.data;
  // distinguish a backend outage from genuinely-empty data (else "—"/"no devices" looks the same)
  const anyError =
    summary.isError ||
    devices.isError ||
    nodes.isError ||
    tasks.isError ||
    values.isError;
  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      {anyError && (
        <ErrorNote error="Couldn't reach the server — showing the last known values." />
      )}
      {/* KPI row */}
      <div className="grid shrink-0 grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard
          label="Success rate"
          value={s ? `${Math.round(s.successRate * 100)}%` : "—"}
          accent="bg-emerald-500/70"
        />
        <StatCard
          label="Collections"
          value={s ? s.collections.total.toLocaleString() : "—"}
          accent="bg-indigo-500/70"
        />
        <StatCard
          label="Live values"
          value={s ? s.liveValues : "—"}
          accent="bg-sky-500/70"
        />
        <StatCard
          label="Devices"
          value={devices.data?.length ?? "—"}
          accent="bg-violet-500/70"
        />
      </div>

      {/* content fills the remaining height; each panel scrolls internally */}
      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-3">
        <Panel
          title={`Live values · ${values.data?.length ?? 0}`}
          className="lg:col-span-2"
          bodyClassName=""
        >
          <table className="w-full text-sm">
            <thead className="sticky top-0 z-10 bg-zinc-900/95 text-[10px] uppercase tracking-wider text-zinc-500 backdrop-blur">
              <tr>
                <th className="px-4 py-2 text-left font-medium">Tag</th>
                <th className="px-4 py-2 text-right font-medium">Value</th>
                <th className="px-4 py-2 text-right font-medium">Quality</th>
              </tr>
            </thead>
            <tbody>
              {values.data?.map((v) => (
                <tr
                  key={v.tagKey}
                  className="border-t border-white/5 hover:bg-white/5"
                >
                  <td className="px-4 py-1.5 text-zinc-300">{v.tagKey}</td>
                  <td className="px-4 py-1.5 text-right font-mono text-zinc-100">
                    {String(v.value)}
                  </td>
                  <td className="px-4 py-1.5 text-right">
                    <Badge tone={qualityTone(v.quality)}>{v.quality}</Badge>
                  </td>
                </tr>
              ))}
              {!values.data?.length && (
                <tr>
                  <td colSpan={3} className="px-4 py-3 text-zinc-500">
                    no live values yet — run a task
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </Panel>

        {/* right rail: devices / nodes / tasks, each its own scrollable panel */}
        <div className="grid min-h-0 grid-rows-3 gap-4">
          <Panel title={`Devices · ${devices.data?.length ?? 0}`}>
            <ul className="space-y-2 text-sm">
              {devices.data?.map((d) => (
                <li key={d.id} className="flex items-center justify-between">
                  <span className="truncate text-zinc-200">{d.name}</span>
                  <span className="flex shrink-0 items-center gap-2">
                    <Badge tone="info">{d.adapterType}</Badge>
                    <Badge
                      tone={
                        d.status === "OK" || d.status === "CONFIGURED"
                          ? "good"
                          : "muted"
                      }
                    >
                      {d.status}
                    </Badge>
                  </span>
                </li>
              ))}
              {!devices.data?.length && (
                <li className="text-zinc-500">no devices</li>
              )}
            </ul>
          </Panel>

          <Panel title={`Nodes · ${nodes.data?.length ?? 0}`}>
            <ul className="space-y-2 text-sm">
              {nodes.data?.map((n) => (
                <li key={n.id} className="flex items-center justify-between">
                  <span className="truncate text-zinc-200">{n.name}</span>
                  <span className="shrink-0 text-zinc-500">
                    {n.retentionHours}h
                  </span>
                </li>
              ))}
              {!nodes.data?.length && (
                <li className="text-zinc-500">no nodes</li>
              )}
            </ul>
          </Panel>

          <Panel title={`Tasks · ${tasks.data?.length ?? 0}`}>
            <ul className="space-y-2 text-sm">
              {tasks.data?.map((t) => (
                <li
                  key={t.id}
                  className="flex items-center justify-between gap-2"
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <Badge tone="info">{t.type}</Badge>
                    <span className="truncate text-zinc-400">
                      {t.scheduleKind}
                      {t.intervalMs ? ` · ${t.intervalMs}ms` : ""}
                    </span>
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={!canOperate()}
                    onClick={() => run.mutate(t.id)}
                  >
                    Run
                  </Button>
                </li>
              ))}
              {!tasks.data?.length && (
                <li className="text-zinc-500">no tasks</li>
              )}
            </ul>
          </Panel>
        </div>
      </div>
    </div>
  );
}

const NAV: { id: View; label: string }[] = [
  { id: "dashboard", label: "Dashboard" },
  { id: "flows", label: "Flows" },
  { id: "tags", label: "Tags" },
  { id: "timemachine", label: "Time Machine" },
  { id: "logs", label: "Logs" },
];

const TITLES: Record<View, string> = {
  dashboard: "Dashboard",
  flows: "Flows",
  tags: "Tags",
  timemachine: "Time Machine",
  logs: "Collection logs",
};

// avatar background by role (ADMIN = indigo, OPERATOR = sky, VIEWER = zinc)
function roleAvatar(role: string): string {
  if (role === "ADMIN") return "bg-indigo-600";
  if (role === "OPERATOR") return "bg-sky-600";
  return "bg-zinc-600";
}

function Shell({ auth, onLogout }: { auth: Auth; onLogout: () => void }) {
  const [view, setView] = useState<View>("dashboard");
  const [now] = useState(() => Date.now());
  const [debugOpen, setDebugOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [userOpen, setUserOpen] = useState(false);

  // honest "live" indicator: shares the dashboard summary query (polls every 5s). If the backend is
  // unreachable the query errors → we show "offline" instead of a permanently-green fake status.
  const health = useQuery({ queryKey: ["summary"], queryFn: api.dashboard });
  const live = !health.isError;

  // close any open dropdown on Escape
  useEffect(() => {
    if (!menuOpen && !userOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setMenuOpen(false);
      setUserOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [menuOpen, userOpen]);

  let content: ReactNode = null;
  if (view === "dashboard") content = <Dashboard />;
  else if (view === "flows") content = <Flows />;
  else if (view === "tags") content = <TagsTree />;
  else if (view === "timemachine") content = <TimeMachine now={now} />;
  else content = <Logs />;

  // Dashboard/Tags/Time Machine now fill the viewport (single-screen, per-panel scroll) like the Flows
  // canvas; only Logs keeps the centered, page-scrolling layout.
  const fullHeight = view !== "logs";

  return (
    <div className="flex h-screen flex-col bg-zinc-950 text-zinc-100">
      <header className="flex shrink-0 items-center justify-between border-b border-white/5 px-6 py-3">
        <div className="flex items-center gap-3">
          <div className="grid h-8 w-8 place-items-center rounded-lg bg-indigo-600 font-bold text-white">
            C
          </div>
          <div className="leading-tight">
            <div className="text-sm font-semibold">Chronos</div>
            <div className="text-[10px] text-zinc-500">IoT Historian</div>
          </div>
          <h1 className="ml-3 border-l border-white/10 pl-4 text-lg font-semibold">
            {TITLES[view]}
          </h1>
        </div>
        <div className="flex items-center gap-3 text-xs">
          <span
            className="hidden items-center gap-1.5 text-[11px] sm:flex"
            title={
              live
                ? "backend reachable (polling every 5s)"
                : "backend unreachable"
            }
          >
            <span
              className={`h-2 w-2 rounded-full ${live ? "animate-pulse bg-emerald-500" : "bg-rose-500"}`}
            />
            <span className={live ? "text-zinc-500" : "text-rose-400"}>
              {live ? "live" : "offline"}
            </span>
          </span>
          {/* user menu: avatar → username / role / log out */}
          <div className="relative">
            <button
              type="button"
              aria-label="Account"
              aria-haspopup="menu"
              aria-expanded={userOpen}
              onClick={() => setUserOpen((o) => !o)}
              className={`grid h-9 w-9 place-items-center rounded-full text-sm font-semibold text-white ring-1 ring-white/10 transition-transform hover:scale-105 ${roleAvatar(auth.role)}`}
              title={`${auth.username} · ${auth.role}`}
            >
              {auth.username.charAt(0).toUpperCase()}
            </button>
            {userOpen && (
              <>
                <button
                  type="button"
                  aria-label="Close menu"
                  className="fixed inset-0 z-30 cursor-default"
                  onClick={() => setUserOpen(false)}
                />
                <div className="absolute right-0 z-40 mt-2 w-56 overflow-hidden rounded-xl border border-white/10 bg-zinc-900 shadow-2xl">
                  <div className="flex items-center gap-3 border-b border-white/5 px-4 py-3">
                    <span
                      className={`grid h-9 w-9 shrink-0 place-items-center rounded-full text-sm font-semibold text-white ${roleAvatar(auth.role)}`}
                    >
                      {auth.username.charAt(0).toUpperCase()}
                    </span>
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold text-zinc-100">
                        {auth.username}
                      </div>
                      <Badge
                        tone={
                          auth.role === "ADMIN"
                            ? "good"
                            : auth.role === "OPERATOR"
                              ? "info"
                              : "muted"
                        }
                      >
                        {auth.role}
                      </Badge>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setUserOpen(false);
                      onLogout();
                    }}
                    className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-zinc-300 hover:bg-white/5 hover:text-rose-300"
                  >
                    <Icon path="M16 17l5-5-5-5M21 12H9M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
                    Log out
                  </button>
                </div>
              </>
            )}
          </div>

          {/* hamburger nav menu */}
          <div className="relative">
            <button
              type="button"
              aria-label="Menu"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((o) => !o)}
              className="flex h-9 w-9 items-center justify-center rounded-lg border border-white/10 text-zinc-300 hover:bg-white/5 hover:text-zinc-100"
            >
              <svg
                viewBox="0 0 24 24"
                className="h-5 w-5"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden="true"
              >
                <path strokeLinecap="round" d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            {menuOpen && (
              <>
                <button
                  type="button"
                  aria-label="Close menu"
                  className="fixed inset-0 z-30 cursor-default"
                  onClick={() => setMenuOpen(false)}
                />
                <div className="absolute right-0 z-40 mt-2 w-52 overflow-hidden rounded-xl border border-white/10 bg-zinc-900 p-1.5 shadow-2xl">
                  {NAV.map((n) => (
                    <button
                      key={n.id}
                      type="button"
                      onClick={() => {
                        setView(n.id);
                        setMenuOpen(false);
                      }}
                      className={`flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors ${
                        view === n.id
                          ? "bg-indigo-500/15 text-indigo-300"
                          : "text-zinc-300 hover:bg-white/5 hover:text-zinc-100"
                      }`}
                    >
                      <Icon path={ICONS[n.id]} />
                      {n.label}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </header>

      {fullHeight ? (
        <main className="flex min-h-0 flex-1 flex-col overflow-hidden p-4">
          <Suspense fallback={<Loading />}>{content}</Suspense>
        </main>
      ) : (
        <main className="flex-1 overflow-auto px-8 py-6">
          <div className="mx-auto max-w-5xl">
            <Suspense fallback={<Loading />}>{content}</Suspense>
          </div>
        </main>
      )}

      {/* Drawer handle: a tab on the right edge that slides with the panel (replaces the header Debug button) */}
      <button
        type="button"
        onClick={() => setDebugOpen((o) => !o)}
        aria-label={debugOpen ? "Close debug panel" : "Open debug panel"}
        aria-expanded={debugOpen}
        title={debugOpen ? "Close debug panel" : "Open debug panel"}
        className={`fixed top-1/2 z-50 flex h-16 w-6 -translate-y-1/2 items-center justify-center rounded-l-lg border border-r-0 border-white/10 bg-zinc-900/95 shadow-lg backdrop-blur transition-[right] duration-200 hover:bg-zinc-800 hover:text-zinc-100 ${
          debugOpen ? "right-80 text-indigo-400" : "right-0 text-zinc-400"
        }`}
      >
        {/* bug icon (debug) */}
        <svg
          viewBox="0 0 24 24"
          className="h-4 w-4"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="m8 2 1.88 1.88M14.12 3.88 16 2" />
          <path d="M9 7.13v-1a3.003 3.003 0 1 1 6 0v1" />
          <path d="M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6M12 20v-9" />
          <path d="M6.53 9C4.6 8.8 3 7.1 3 5M6 13H2M3 21c0-2.1 1.7-3.9 3.8-4" />
          <path d="M20.97 5c0 2.1-1.6 3.8-3.5 4M22 13h-4M17.2 17c2.1.1 3.8 1.9 3.8 4" />
        </svg>
      </button>

      <DebugPanel open={debugOpen} onClose={() => setDebugOpen(false)} />
      <Toaster />
    </div>
  );
}

export function App() {
  const [auth, setAuthState] = useState<Auth | null>(() => getAuth());

  // a 401 anywhere (expired token) → drop to the login screen
  useEffect(() => {
    const onUnauth = () => setAuthState(null);
    window.addEventListener("chronos-unauthorized", onUnauth);
    return () => window.removeEventListener("chronos-unauthorized", onUnauth);
  }, []);

  if (!auth) {
    return <Login onSuccess={() => setAuthState(getAuth())} />;
  }
  const logout = () => {
    clearAuth();
    setAuthState(null);
  };
  return (
    <QueryClientProvider client={queryClient}>
      <Shell auth={auth} onLogout={logout} />
    </QueryClientProvider>
  );
}
