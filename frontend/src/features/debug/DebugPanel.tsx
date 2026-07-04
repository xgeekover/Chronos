import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, type CollectionLog, type FlowAudit } from "../../api/client";
import { Badge, Button, inputClass } from "../../ui/kit";
import {
  clearDebug,
  type DebugMessage,
  type NodeInfo,
  subscribeActiveFlow,
  subscribeDebug,
  subscribeSelection,
} from "./debugBus";
import { JsonView, jsonText } from "./JsonView";

type Tab = "debug" | "system" | "info" | "context";

/**
 * Node-RED-style right slide-out sidebar with tabs:
 *  • Debug  — live messages streamed from canvas Debug nodes (one per sample, newest first)
 *  • System — flow deploys + collection runs (pipeline-wide health)
 *  • Info   — details of the currently-selected canvas node
 */
export function DebugPanel({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const [tab, setTab] = useState<Tab>("debug");

  const [msgs, setMsgs] = useState<DebugMessage[]>([]);
  useEffect(() => subscribeDebug(setMsgs), []);

  const [info, setInfo] = useState<NodeInfo | null>(null);
  useEffect(() => subscribeSelection(setInfo), []);

  const [activeFlow, setActiveFlow] = useState<string | null>(null);
  useEffect(() => subscribeActiveFlow(setActiveFlow), []);

  const sysActive = open && tab === "system";
  const logs = useQuery({
    queryKey: ["logs"],
    queryFn: api.logs,
    refetchInterval: sysActive ? 3000 : false,
    enabled: sysActive,
  });
  const flowAudit = useQuery({
    queryKey: ["flowAudit"],
    queryFn: api.flowAudit,
    refetchInterval: sysActive ? 3000 : false,
    enabled: sysActive,
  });
  const ctxActive = open && tab === "context" && !!activeFlow;
  const ctx = useQuery({
    queryKey: ["flowContext", activeFlow],
    queryFn: () => api.flowContext(activeFlow ?? ""),
    refetchInterval: ctxActive ? 2000 : false,
    enabled: ctxActive,
  });

  const TABS: { id: Tab; label: string }[] = [
    { id: "debug", label: `Debug${msgs.length ? ` (${msgs.length})` : ""}` },
    { id: "system", label: "System" },
    { id: "info", label: "Info" },
    { id: "context", label: "Context" },
  ];

  return (
    <aside
      className={`fixed right-0 top-0 z-40 flex h-screen w-80 transform flex-col border-l border-white/10 bg-zinc-900/95 shadow-2xl backdrop-blur transition-transform duration-200 ${
        open ? "translate-x-0" : "translate-x-full"
      }`}
      aria-hidden={!open}
      inert={!open}
    >
      <header className="flex items-center justify-between border-b border-white/5 px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-zinc-100">
          <span className="h-2 w-2 animate-pulse rounded-full bg-emerald-500" />
          Inspector
        </h2>
        <button
          type="button"
          onClick={onClose}
          className="text-zinc-500 hover:text-zinc-200"
        >
          ✕
        </button>
      </header>

      <div className="flex shrink-0 border-b border-white/5 px-2 pt-1 text-xs">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            className={`-mb-px border-b-2 px-3 py-2 transition-colors ${
              tab === t.id
                ? "border-indigo-400 text-indigo-300"
                : "border-transparent text-zinc-500 hover:text-zinc-300"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-auto px-3 py-3 text-xs">
        {tab === "debug" && <DebugTab msgs={msgs} />}
        {tab === "system" && (
          <SystemTab
            logs={logs.data}
            audit={flowAudit.data}
            loading={!logs.data}
          />
        )}
        {tab === "info" && <InfoTab info={info} />}
        {tab === "context" && (
          <ContextTab flow={ctx.data?.flow} global={ctx.data?.global} />
        )}
      </div>
    </aside>
  );
}

function ContextTab({
  flow,
  global,
}: {
  flow?: Record<string, unknown>;
  global?: Record<string, unknown>;
}) {
  const section = (title: string, vars?: Record<string, unknown>) => {
    const entries = Object.entries(vars ?? {});
    return (
      <>
        <div className="mb-1 mt-2 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
          {title}
        </div>
        {entries.length === 0 ? (
          <p className="text-zinc-500">empty</p>
        ) : (
          <table className="mb-3 w-full">
            <tbody>
              {entries.map(([k, v]) => (
                <tr key={k} className="border-t border-white/5 align-top">
                  <td className="py-1 pr-3 text-amber-300">{k}</td>
                  <td className="min-w-0 py-1 font-mono text-zinc-200">
                    <JsonView value={v} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </>
    );
  };
  return (
    <>
      <p className="mb-1 leading-relaxed text-zinc-500">
        flow/global variables set by function nodes (
        <span className="font-mono text-zinc-400">flow.put(k,v)</span> /{" "}
        <span className="font-mono text-zinc-400">global.put(k,v)</span>).
      </p>
      {section("flow", flow)}
      {section("global", global)}
    </>
  );
}

function DebugTab({ msgs }: { msgs: DebugMessage[] }) {
  const [q, setQ] = useState("");
  if (msgs.length === 0) {
    return (
      <p className="py-6 leading-relaxed text-zinc-500">
        No debug messages yet. Drop a{" "}
        <span className="text-slate-300">Debug</span> node in a flow and wire a
        message into it — each one prints here, newest first.
      </p>
    );
  }
  const needle = q.trim().toLowerCase();
  const shown = needle
    ? msgs.filter(
        (m) =>
          String(m.topic).toLowerCase().includes(needle) ||
          jsonText(m.value).toLowerCase().includes(needle),
      )
    : msgs;
  return (
    <>
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
          {needle
            ? `${shown.length}/${msgs.length}`
            : `messages · ${msgs.length}`}
        </span>
        <Button variant="ghost" onClick={clearDebug}>
          Clear
        </Button>
      </div>
      <input
        className={`${inputClass} mb-2 w-full`}
        placeholder="filter by topic or value…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />
      <ul className="space-y-1.5">
        {shown.map((m) => (
          <li
            key={m.id}
            className="rounded-md border border-white/5 bg-zinc-950/40 p-2"
          >
            <div className="flex items-center justify-between gap-2">
              <span className="truncate font-mono text-sky-300">{m.topic}</span>
              <span className="shrink-0 text-zinc-500">{m.at}</span>
            </div>
            <div className="mt-1 flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1 font-mono text-zinc-100">
                <JsonView value={m.value} />
              </div>
              {m.quality && (
                <Badge tone={m.quality === "GOOD" ? "good" : "bad"}>
                  {m.quality.toLowerCase()}
                </Badge>
              )}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

function auditTone(action: string) {
  if (action === "DEPLOY") return "good" as const;
  if (action === "ERROR") return "bad" as const;
  return "muted" as const;
}

function SystemTab({
  logs,
  audit,
  loading,
}: {
  logs?: CollectionLog[];
  audit?: FlowAudit[];
  loading: boolean;
}) {
  return (
    <>
      <div className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
        Flow deploys
      </div>
      <ul className="mb-4 space-y-1.5">
        {audit?.slice(0, 15).map((a) => (
          <li
            key={a.id}
            className="rounded-md border border-white/5 bg-zinc-950/40 p-2"
          >
            <div className="flex items-center justify-between gap-2">
              <Badge tone={auditTone(a.action)}>{a.action}</Badge>
              <span className="shrink-0 text-zinc-500">
                {new Date(a.at).toLocaleTimeString()}
              </span>
            </div>
            <div className="mt-1 truncate text-zinc-300">
              {a.flowName || "(unnamed)"}
              {a.nodeCount != null && (
                <span className="text-zinc-500"> · {a.nodeCount} nodes</span>
              )}
            </div>
            <div className="text-zinc-500">
              by {a.actor}
              {a.detail && a.detail !== "ok" && (
                <span className="text-rose-300"> · {a.detail}</span>
              )}
            </div>
          </li>
        ))}
        {audit?.length === 0 && (
          <li className="text-zinc-500">no deploys yet</li>
        )}
      </ul>

      <div className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
        Collection runs
      </div>
      <ul className="mb-4 space-y-1.5">
        {logs?.slice(0, 25).map((l) => (
          <li
            key={l.id}
            className="rounded-md border border-white/5 bg-zinc-950/40 p-2"
          >
            <div className="flex items-center justify-between">
              <Badge tone={l.status === "OK" ? "good" : "bad"}>
                {l.status}
              </Badge>
              <span className="text-zinc-500">
                {new Date(l.startedAt).toLocaleTimeString()}
              </span>
            </div>
            <div className="mt-1 text-zinc-500">
              {l.durationMs ?? "-"}ms · {l.tagCount ?? 0} tags
            </div>
            {l.error && (
              <div className="mt-1 break-words text-rose-300">{l.error}</div>
            )}
          </li>
        ))}
        {logs?.length === 0 && <li className="text-zinc-500">no runs yet</li>}
        {loading && <li className="text-zinc-500">loading…</li>}
      </ul>
    </>
  );
}

function InfoTab({ info }: { info: NodeInfo | null }) {
  if (!info) {
    return (
      <p className="py-6 leading-relaxed text-zinc-500">
        Select a node on the canvas to see its type, help and settings here.
      </p>
    );
  }
  return (
    <>
      <div className="mb-2 flex items-center gap-2">
        <Badge tone="info">{info.type}</Badge>
        <span className="truncate font-semibold text-zinc-100">
          {info.title}
        </span>
      </div>
      {info.help && (
        <p className="mb-3 leading-relaxed text-zinc-400">{info.help}</p>
      )}
      <table className="w-full">
        <tbody>
          {info.fields.map(([k, v]) => (
            <tr key={k} className="border-t border-white/5 align-top">
              <td className="py-1 pr-3 text-zinc-500">{k}</td>
              <td className="py-1 break-all font-mono text-zinc-200">{v}</td>
            </tr>
          ))}
          {info.fields.length === 0 && (
            <tr>
              <td className="py-1 text-zinc-500">no details</td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  );
}
