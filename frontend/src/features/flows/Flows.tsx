import {
  addEdge,
  Background,
  BackgroundVariant,
  BaseEdge,
  type Connection,
  Controls,
  type Edge,
  EdgeLabelRenderer,
  type EdgeProps,
  getBezierPath,
  Handle,
  MiniMap,
  type Node,
  type NodeProps,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { type DragEvent, useEffect, useRef, useState } from "react";
import {
  api,
  type DiffResult,
  type FileChange,
  type FlowMetrics,
  isAdmin,
  type ProjectCommit,
  type ProjectStatus,
} from "../../api/client";
import { Button, Icon, inputClass, Modal } from "../../ui/kit";
import { toast } from "../../ui/toast";
import {
  clearDebug,
  publishActiveFlow,
  publishDebug,
  publishSelection,
} from "../debug/debugBus";
import { FunctionCodeField } from "../scripts/FunctionCodeField";

// ───────── node catalog ─────────
// Palette entries carry a category so the sidebar groups them (Node-RED-style) for findability.
const PALETTE: { type: string; label: string; cat: string }[] = [
  // ── Common ──
  { type: "inject", label: "Inject", cat: "common" },
  { type: "debug", label: "Debug", cat: "common" },
  { type: "complete", label: "Complete", cat: "common" },
  { type: "catch", label: "Catch", cat: "common" },
  { type: "status", label: "Status", cat: "common" },
  { type: "linkin", label: "Link in", cat: "common" },
  { type: "linkout", label: "Link out", cat: "common" },
  { type: "linkcall", label: "Link call", cat: "common" },
  { type: "junction", label: "Junction", cat: "common" },
  { type: "comment", label: "Comment", cat: "common" },
  // ── Function (logic & scripting) ──
  { type: "function", label: "Function (JS)", cat: "function" },
  { type: "switch", label: "Switch", cat: "function" },
  { type: "change", label: "Change", cat: "function" },
  { type: "range", label: "Range", cat: "function" },
  { type: "template", label: "Template", cat: "function" },
  { type: "delay", label: "Delay", cat: "function" },
  { type: "trigger", label: "Trigger", cat: "function" },
  { type: "rbe", label: "Filter (rbe)", cat: "function" },
  { type: "exec", label: "Exec", cat: "function" },
  // ── Network ──
  { type: "httpin", label: "HTTP in", cat: "network" },
  { type: "httpresponse", label: "HTTP response", cat: "network" },
  { type: "httprequest", label: "HTTP request", cat: "network" },
  { type: "mqttin", label: "MQTT in", cat: "network" },
  { type: "mqttout", label: "MQTT out", cat: "network" },
  { type: "wsin", label: "WebSocket in", cat: "network" },
  { type: "wsout", label: "WebSocket out", cat: "network" },
  { type: "tcpin", label: "TCP in", cat: "network" },
  { type: "tcpout", label: "TCP out", cat: "network" },
  { type: "udpin", label: "UDP in", cat: "network" },
  { type: "udpout", label: "UDP out", cat: "network" },
  { type: "soaprequest", label: "SOAP request", cat: "network" },
  // ── Sequence ──
  { type: "split", label: "Split", cat: "sequence" },
  { type: "join", label: "Join", cat: "sequence" },
  { type: "sort", label: "Sort", cat: "sequence" },
  { type: "batch", label: "Batch", cat: "sequence" },
  // ── Parser (format convert) ──
  { type: "csv", label: "CSV", cat: "parser" },
  { type: "json", label: "JSON", cat: "parser" },
  { type: "xml", label: "XML", cat: "parser" },
  { type: "yaml", label: "YAML", cat: "parser" },
  { type: "html", label: "HTML", cat: "parser" },
  // ── Storage (files & historian) ──
  { type: "filein", label: "File in", cat: "storage" },
  { type: "fileout", label: "File out", cat: "storage" },
  { type: "tag", label: "Tag (historian)", cat: "storage" },
  { type: "deviceread", label: "Device read", cat: "storage" },
];
// Palette section order + display labels (each PALETTE entry's `cat` maps to one of these).
const CATEGORIES: { id: string; label: string }[] = [
  { id: "common", label: "Common" },
  { id: "function", label: "Function" },
  { id: "network", label: "Network" },
  { id: "sequence", label: "Sequence" },
  { id: "parser", label: "Parser" },
  { id: "storage", label: "Storage" },
];
const NODE_COLOR: Record<string, string> = {
  inject: "bg-emerald-600/90",
  httpin: "bg-teal-600/90",
  mqttin: "bg-purple-700/90",
  tcpin: "bg-sky-800/90",
  wsin: "bg-fuchsia-700/90",
  function: "bg-amber-600/90",
  change: "bg-amber-600/90",
  switch: "bg-orange-600/90",
  rbe: "bg-orange-700/90",
  template: "bg-amber-700/90",
  range: "bg-amber-600/90",
  split: "bg-amber-700/90",
  join: "bg-amber-700/90",
  sort: "bg-amber-700/90",
  batch: "bg-amber-700/90",
  csv: "bg-amber-700/90",
  json: "bg-orange-700/90",
  xml: "bg-orange-700/90",
  yaml: "bg-orange-700/90",
  html: "bg-orange-700/90",
  delay: "bg-sky-700/90",
  trigger: "bg-sky-600/90",
  exec: "bg-red-800/90",
  deviceread: "bg-teal-700/90",
  comment: "bg-yellow-500/90",
  httprequest: "bg-cyan-700/90",
  soaprequest: "bg-indigo-700/90",
  mqttout: "bg-purple-800/90",
  tcpout: "bg-sky-900/90",
  udpin: "bg-sky-800/90",
  udpout: "bg-sky-900/90",
  filein: "bg-lime-700/90",
  fileout: "bg-lime-800/90",
  tag: "bg-blue-700/90",
  wsout: "bg-fuchsia-800/90",
  httpresponse: "bg-teal-800/90",
  linkin: "bg-stone-600/90",
  linkout: "bg-stone-600/90",
  linkcall: "bg-stone-500/90",
  subin: "bg-violet-700/90",
  subout: "bg-violet-700/90",
  subflow: "bg-violet-600/90",
  catch: "bg-rose-700/90",
  complete: "bg-slate-700/90",
  status: "bg-slate-700/90",
  junction: "bg-zinc-600/90",
  debug: "bg-slate-600/90",
};
// MiniMap needs real colors (not Tailwind classes) — derive the hex from each node's accent family so
// the map dots match the canvas and never drift from NODE_COLOR.
const MINIMAP_FAMILY_HEX: Record<string, string> = {
  emerald: "#059669",
  teal: "#0d9488",
  purple: "#7e22ce",
  sky: "#0284c7",
  fuchsia: "#c026d3",
  amber: "#d97706",
  orange: "#ea580c",
  red: "#b91c1c",
  cyan: "#0891b2",
  indigo: "#4f46e5",
  lime: "#65a30d",
  blue: "#1d4ed8",
  stone: "#78716c",
  violet: "#7c3aed",
  rose: "#be123c",
  slate: "#475569",
  zinc: "#52525b",
  yellow: "#eab308",
};
function nodeMiniColor(type?: string): string {
  const family = ((type && NODE_COLOR[type]) || "").match(/bg-([a-z]+)-/)?.[1];
  return (family && MINIMAP_FAMILY_HEX[family]) || "#6b7280";
}
const NIB =
  "!h-3 !w-2.5 !min-w-0 !rounded-[3px] !border !border-zinc-900/80 !bg-zinc-300";

// one-line help per node type (shown in the Inspector Info tab when a node is selected)
const NODE_HELP: Record<string, string> = {
  inject: "Starts a flow — fires a message on an interval or via the ▸ button.",
  httpin:
    "Public webhook ingress at /api/flows/in/<path>; wire to HTTP response to reply.",
  mqttin: "Subscribes to an MQTT broker topic and emits a message per arrival.",
  tcpin:
    "Connects to a TCP host:port and emits each received line (auto-reconnects).",
  wsin: "Connects to a WebSocket URL and emits each text frame (auto-reconnects).",
  function:
    "Runs JavaScript (sandboxed, Node-RED-style: msg/node/flow/global/env) or Java. Return msg or use node.send() for multiple outputs.",
  change: "Applies set / change / delete / move rules to message properties.",
  switch:
    "Routes the message to an output per matching rule (operators or an expression).",
  rbe: "Report-by-exception: passes a message only when its payload changed.",
  template: "Fills a {{ expression }} template into a property.",
  range: "Linearly scales a numeric property from one range to another.",
  split: "Splits an array or delimited string into one message per element.",
  join: "Collects N messages into a single array payload.",
  sort: "Sorts a list payload (asc/desc, numeric or string, optional key).",
  batch: "Groups messages into one array payload — by count or on an interval.",
  csv: "Splits a delimited string payload into a trimmed array.",
  json: "Parses a JSON string ↔ object (auto by payload type).",
  xml: "Parses an XML string ↔ nested object.",
  yaml: "Parses a YAML string ↔ object.",
  html: "Extracts elements matching a CSS selector from an HTML payload.",
  delay: "Delays each message by a fixed time.",
  trigger: "Emits the input, then a configured payload after a delay.",
  exec: "Runs a system command; outputs stdout(0), stderr(1), return code(2).",
  deviceread:
    "Runs a pull adapter (JDBC/Modbus/SHELL/FILE/API/SCRIPT_JAVA) on each input → emits the raw result. Wire an inject timer in to poll on a schedule.",
  udpin: "Binds a UDP port and emits each received datagram as payload.",
  udpout: "Sends msg.payload as a UDP datagram to host:port.",
  filein: "Reads a file (under the flow files dir) into msg.payload.",
  fileout: "Writes / appends / deletes a file under the flow files dir.",
  tag: "Writes msg.payload to a historian tag (canonicalKey, e.g. line1.temp) — feeds Time Machine and the gateway. The tag must already exist.",
  httprequest:
    "Makes an outbound HTTP request; response → msg.payload, status → msg.statusCode.",
  soaprequest: "POSTs a SOAP envelope (built from a template) to an endpoint.",
  mqttout: "Publishes msg.payload to an MQTT broker topic.",
  tcpout: "Writes msg.payload (+newline) to a TCP host:port.",
  wsout: "Sends msg.payload as a WebSocket text frame.",
  httpresponse:
    "Replies to the matching HTTP in caller with msg.payload / status / headers.",
  linkin: "Receives messages from Link out nodes over a virtual wire.",
  linkout:
    "Forwards to the linked Link in nodes; in 'return' mode, sends back to the Link call.",
  linkcall:
    "Calls a Link in subroutine and continues when a return-mode Link out replies.",
  catch: "Catches errors from watched nodes; msg.error carries the details.",
  complete: "Fires when a watched node finishes handling a message.",
  status:
    "Receives status events from watched nodes; msg.status carries text + source.",
  junction:
    "A wire pass-through point — forwards its input unchanged (tidy routing).",
  debug: "Prints the message to the Inspector Debug tab.",
  comment: "A canvas note — not deployed.",
  group:
    "A container box; drag it to move its child nodes together. Not deployed.",
  subin: "Subflow input marker — the entry point of a subflow template.",
  subout: "Subflow output marker — the exit point of a subflow template.",
  subflow:
    "An instance of a subflow template, inlined into this flow at deploy.",
};

type Cfg = Record<string, unknown>;

function defaultConfig(type: string): Cfg {
  switch (type) {
    case "inject":
      return { intervalMs: 2000, payload: 42, topic: "sensor/x" };
    case "json":
    case "xml":
    case "yaml":
      return { action: "auto" };
    case "sort":
      return { order: "asc", numeric: false };
    case "batch":
      return { mode: "count", count: 10, intervalMs: 1000 };
    case "html":
      return { selector: "a", output: "text" };
    case "exec":
      return { command: "", args: "", useShell: false, timeoutMs: 10000 };
    case "deviceread":
      return {
        adapterType: "JDBC",
        taskType: "QUERY",
        params: {},
        definition: {},
        secrets: {},
        timeoutMs: 10000,
      };
    case "udpin":
      return { port: 9100 };
    case "udpout":
      return { host: "127.0.0.1", port: 9100 };
    case "tag":
      return { tag: "" };
    case "filein":
      return { filename: "in.txt" };
    case "fileout":
      return { filename: "out.txt", action: "append" };
    case "change":
      return { rules: [{ t: "set", p: "payload", to: "hello", tot: "str" }] };
    case "switch":
      return {
        property: "payload",
        rules: [{ op: "gt", value: 10 }, { op: "else" }],
      };
    case "template":
      return { property: "payload", template: "value = {{payload}}" };
    case "range":
      return {
        property: "payload",
        inMin: 0,
        inMax: 100,
        outMin: 0,
        outMax: 1,
      };
    case "delay":
      return { ms: 1000 };
    case "rbe":
      return { mode: "rbe" };
    case "trigger":
      return { delayMs: 1000, thenPayload: "reset" };
    case "csv":
      return { delimiter: "," };
    case "comment":
      return { text: "note…" };
    case "catch":
    case "complete":
    case "status":
      return { scope: [] }; // empty = all nodes in the flow
    case "linkin":
      return {};
    case "linkout":
      return { links: [] }; // ids of link-in nodes to forward to
    case "linkcall":
      return { links: [] }; // ids of link-in nodes to invoke (returns via a return-mode link-out)
    case "subin":
    case "subout":
      return { port: 0 };
    case "subflow":
      return { ref: "", outputs: 1 }; // id of the subflow template + its output-port count
    case "function":
      return {
        lang: "js",
        code: "// Node-RED-style. Mutate msg and return it, or use node.send(...).\n// flow.get/set, global.get/set, env.get, node.status/error/warn also available.\nmsg.payload = msg.payload;\nreturn msg;",
        timeoutMs: 5000,
        outputs: 1,
      };
    case "httprequest":
      return { method: "GET", url: "https://api.example.com/data" };
    case "soaprequest":
      return {
        url: "https://example.com/service",
        soapAction: "",
        envelope:
          '<?xml version="1.0"?>\n<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">\n  <soap:Body>{{payload}}</soap:Body>\n</soap:Envelope>',
      };
    case "split":
      return { delimiter: "\n" };
    case "join":
      return { count: 2 };
    case "httpin":
      return { path: "hook", method: "POST" };
    case "httpresponse":
      return {};
    case "mqttin":
      return { brokerUrl: "tcp://mosquitto:1883", topic: "sensors/#", qos: 0 };
    case "mqttout":
      return {
        brokerUrl: "tcp://mosquitto:1883",
        topic: "sensors/out",
        qos: 0,
        retain: false,
      };
    case "tcpin":
    case "tcpout":
      return { host: "localhost", port: 5000 };
    case "wsin":
    case "wsout":
      return { url: "ws://localhost:8080/ws" };
    default:
      return { name: "debug", property: "payload" };
  }
}

function summary(type: string, c: Cfg): string {
  switch (type) {
    case "inject":
      return `every ${c.intervalMs}ms · ${c.payload}`;
    case "change": {
      const rules = (c.rules as unknown[]) ?? [];
      if (rules.length === 0 && c.property) return `${c.property} = ${c.value}`; // legacy single-set
      const first = rules[0] as { t?: string; p?: string } | undefined;
      return rules.length === 1 && first
        ? `${first.t ?? "set"} ${first.p ?? ""}`
        : `${rules.length} rules`;
    }
    case "switch":
      return `${(c.rules as unknown[])?.length ?? 0} routes on ${c.property}`;
    case "template":
      return String(c.template ?? "");
    case "range":
      return `${c.inMin}–${c.inMax} → ${c.outMin}–${c.outMax}`;
    case "delay":
      return `${c.ms}ms`;
    case "rbe":
      return "block unless changed";
    case "trigger":
      return `then "${c.thenPayload}" after ${c.delayMs}ms`;
    case "csv":
      return `split by "${c.delimiter}"`;
    case "json":
    case "xml":
    case "yaml":
      return `${type} · ${c.action ?? "auto"}`;
    case "sort":
      return `sort ${c.order ?? "asc"}${c.numeric ? " (num)" : ""}`;
    case "batch":
      return c.mode === "interval"
        ? `every ${c.intervalMs}ms`
        : `groups of ${c.count}`;
    case "html":
      return `select "${c.selector}"`;
    case "exec":
      return c.command ? `$ ${c.command}` : "$ (from payload)";
    case "deviceread":
      return `read ${c.adapterType ?? "?"}`;
    case "udpin":
      return `:${c.port}`;
    case "udpout":
      return `${c.host}:${c.port}`;
    case "filein":
      return `read ${c.filename}`;
    case "fileout":
      return `${c.action} ${c.filename}`;
    case "tag":
      return c.tag ? `→ ${c.tag}` : "→ (set tag)";
    case "comment":
      return String(c.text ?? "");
    case "catch": {
      const n = (c.scope as unknown[])?.length ?? 0;
      return n ? `catch ${n} node(s)` : "catch all errors";
    }
    case "complete": {
      const n = (c.scope as unknown[])?.length ?? 0;
      return n ? `on ${n} node(s) done` : "on any node done";
    }
    case "status": {
      const n = (c.scope as unknown[])?.length ?? 0;
      return n ? `status of ${n} node(s)` : "all node statuses";
    }
    case "junction":
      return "pass-through";
    case "linkin":
      return "virtual link target";
    case "linkout": {
      const n = (c.links as unknown[])?.length ?? 0;
      return c.mode === "return"
        ? "↩ return to caller"
        : n
          ? `→ ${n} link(s)`
          : "→ (no links)";
    }
    case "linkcall": {
      const n = (c.links as unknown[])?.length ?? 0;
      return n ? `⟳ call ${n} link(s)` : "⟳ call (no target)";
    }
    case "subin":
      return `▸ input ${c.port ?? 0}`;
    case "subout":
      return `output ${c.port ?? 0} ▪`;
    case "subflow":
      return String(c.name ?? "subflow");
    case "function":
      return c.lang === "js" ? "JS fn" : "Java fn";
    case "httprequest":
      return `${c.method} ${c.url}`;
    case "soaprequest":
      return `SOAP ${c.url}`;
    case "split":
      return `by "${c.delimiter}"`;
    case "join":
      return `${c.count} msgs → array`;
    case "httpin":
      return `${c.method} /in/${c.path}`;
    case "httpresponse":
      return "respond to caller";
    case "mqttin":
      return `sub ${c.topic}`;
    case "mqttout":
      return `pub ${c.topic}`;
    case "tcpin":
      return `← ${c.host}:${c.port}`;
    case "tcpout":
      return `→ ${c.host}:${c.port}`;
    case "wsin":
      return `← ${c.url}`;
    case "wsout":
      return `→ ${c.url}`;
    default:
      return String(c.name ?? "debug");
  }
}

// ───────── pre-deploy validation: catch common foot-guns before sending to the runtime ─────────
type Issue = { level: "error" | "warn"; nodeId?: string; message: string };
// nodes with no message input (pure sources or event-driven: catch/complete are fired by the runtime)
const SOURCE_TYPES = [
  "inject",
  "httpin",
  "mqttin",
  "tcpin",
  "udpin",
  "wsin",
  "catch",
  "complete",
  "status",
  "linkin",
  "subin",
];
const SINK_TYPES = ["debug", "httpresponse", "linkout", "subout", "tag"];

function validateGraph(
  nodes: Node[],
  edges: Edge[],
  brokers: Broker[],
): Issue[] {
  const issues: Issue[] = [];
  const live = nodes.filter((n) => n.type !== "comment" && n.type !== "group");
  if (live.length === 0) {
    issues.push({
      level: "error",
      message: "Flow is empty — add a node first.",
    });
    return issues;
  }
  const ids = new Set(live.map((n) => n.id));
  const outCount = new Map<string, number>();
  const inCount = new Map<string, number>();
  for (const e of edges) {
    if (ids.has(e.source))
      outCount.set(e.source, (outCount.get(e.source) ?? 0) + 1);
    if (ids.has(e.target))
      inCount.set(e.target, (inCount.get(e.target) ?? 0) + 1);
  }
  const httpPaths = new Map<string, number>();
  for (const n of live) {
    const t = String(n.type);
    const c = (n.data.config ?? {}) as Cfg;
    const inc = inCount.get(n.id) ?? 0;
    const out = outCount.get(n.id) ?? 0;
    if (!SOURCE_TYPES.includes(t) && inc === 0)
      issues.push({
        level: "warn",
        nodeId: n.id,
        message: `${t}: no input wire`,
      });
    if (!SINK_TYPES.includes(t) && out === 0)
      issues.push({
        level: "warn",
        nodeId: n.id,
        message: `${t}: output goes nowhere`,
      });
    if (t === "switch" && ((c.rules as unknown[]) ?? []).length === 0)
      issues.push({
        level: "error",
        nodeId: n.id,
        message: "switch has no rules",
      });
    if (t === "function" && !String(c.code ?? "").trim())
      issues.push({
        level: "error",
        nodeId: n.id,
        message: "function has no code",
      });
    if ((t === "mqttin" || t === "mqttout") && !String(c.topic ?? "").trim())
      issues.push({
        level: "error",
        nodeId: n.id,
        message: `${t} has no topic`,
      });
    if (
      (t === "mqttin" || t === "mqttout") &&
      c.broker &&
      !brokers.find((b) => b.id === c.broker)
    )
      issues.push({
        level: "error",
        nodeId: n.id,
        message: `${t} references a deleted broker`,
      });
    if (t === "httpin") {
      const p = String(c.path ?? "").trim();
      if (!p)
        issues.push({
          level: "error",
          nodeId: n.id,
          message: "httpin has no path",
        });
      else httpPaths.set(p, (httpPaths.get(p) ?? 0) + 1);
    }
  }
  for (const [p, count] of httpPaths)
    if (count > 1)
      issues.push({
        level: "error",
        message: `duplicate httpin path "/${p}" (${count} nodes)`,
      });
  return issues;
}

// ───────── deploy-time flattening (brokers resolved + subflow instances inlined) ─────────
type RtNode = { id: string; type: string | undefined; config: Cfg };
type RtWire = { source: string; port: number; target: string };

type WireSrc = { src: string; port: number };
type Expanded = {
  nodes: RtNode[];
  wires: RtWire[];
  inPorts: Map<number, string[]>; // input port → target node ids this flow's input feeds
  outPorts: Map<number, WireSrc[]>; // output port → sources feeding it
};

// Recursively flatten one flow: subflow instances are inlined (nested, prefixed ids), subin/subout
// markers become the flow's exposed in/out ports. Single input (port 0); multiple outputs supported.
function expandFlow(
  rfNodes: Node[],
  rfEdges: Edge[],
  flowsById: Map<string, SavedFlow>,
  brokers: Broker[],
  configs: SharedConfig[],
  env: Record<string, string>,
  prefix: string,
  depth: number,
): Expanded {
  // comment/group are editor-only; disabled nodes are excluded from the deployed graph (Node-RED)
  const canvasOnly = (n: Node) =>
    n.type === "comment" ||
    n.type === "group" ||
    (n.data?.config as { disabled?: boolean } | undefined)?.disabled === true;
  const excluded = new Set(rfNodes.filter(canvasOnly).map((n) => n.id));
  const pfx = (id: string) => prefix + id;
  const subst = (s: string) =>
    s.replace(/\$\{(\w+)\}/g, (_, k) => env[k] ?? "");
  const resolve = (n: Node): Cfg => {
    let config = (n.data.config ?? {}) as Cfg;
    if ((n.type === "mqttin" || n.type === "mqttout") && config.broker) {
      const b = brokers.find((x) => x.id === config.broker);
      if (b)
        config = {
          ...config,
          brokerUrl: b.brokerUrl,
          username: b.username,
          password: b.password,
        };
    }
    // merge a referenced shared config node's props (shared values win for their keys)
    if (config.configRef) {
      const shared = configs.find((x) => x.id === config.configRef);
      if (shared) config = { ...config, ...shared.props };
    }
    let changed = false;
    const out: Cfg = {};
    for (const [k, v] of Object.entries(config)) {
      if (typeof v === "string" && v.includes("${")) {
        out[k] = subst(v);
        changed = true;
      } else out[k] = v;
    }
    return changed ? out : config;
  };

  const nodes: RtNode[] = [];
  const wires: RtWire[] = [];
  const inPorts = new Map<number, string[]>();
  const outPorts = new Map<number, WireSrc[]>();
  const live = rfNodes.filter((n) => !canvasOnly(n));
  const subins = new Map<string, number>(); // subin node id → port
  const subouts = new Map<string, number>(); // subout node id → port
  const instIn = new Map<string, string[]>(); // instance id → its input-0 targets (prefixed)
  const instOut = new Map<string, Map<number, WireSrc[]>>(); // instance id → outputPort → sources

  for (const n of live) {
    const cfg = (n.data.config ?? {}) as Cfg;
    if (n.type === "subin") {
      subins.set(n.id, Number(cfg.port ?? 0));
    } else if (n.type === "subout") {
      subouts.set(n.id, Number(cfg.port ?? 0));
    } else if (n.type === "subflow") {
      const ref = cfg.ref as string | undefined;
      const def = ref ? flowsById.get(ref) : undefined;
      if (!def || depth > 8) continue; // missing ref or recursion guard
      const inner = expandFlow(
        def.nodes,
        def.edges,
        flowsById,
        brokers,
        configs,
        env,
        `${pfx(n.id)}__`,
        depth + 1,
      );
      nodes.push(...inner.nodes);
      wires.push(...inner.wires);
      instIn.set(n.id, inner.inPorts.get(0) ?? []);
      instOut.set(n.id, inner.outPorts);
    } else {
      nodes.push({ id: pfx(n.id), type: n.type, config: resolve(n) });
    }
  }

  const targetsOf = (e: Edge): string[] =>
    instIn.has(e.target) ? (instIn.get(e.target) ?? []) : [pfx(e.target)];
  const sourcesOf = (e: Edge): WireSrc[] => {
    const port = Number(e.sourceHandle ?? 0);
    if (instOut.has(e.source)) return instOut.get(e.source)?.get(port) ?? [];
    return [{ src: pfx(e.source), port }];
  };

  for (const e of rfEdges) {
    if (excluded.has(e.source) || excluded.has(e.target)) continue; // drop wires to disabled nodes
    if (subins.has(e.source)) {
      // an edge FROM this flow's input marker → exposes input port → targets
      const p = subins.get(e.source) ?? 0;
      inPorts.set(p, [...(inPorts.get(p) ?? []), ...targetsOf(e)]);
      continue;
    }
    const sources = sourcesOf(e);
    if (subouts.has(e.target)) {
      // an edge INTO this flow's output marker → exposes output port → sources
      const p = subouts.get(e.target) ?? 0;
      outPorts.set(p, [...(outPorts.get(p) ?? []), ...sources]);
      continue;
    }
    for (const t of targetsOf(e))
      for (const s of sources)
        wires.push({ source: s.src, port: s.port, target: t });
  }
  return { nodes, wires, inPorts, outPorts };
}

function buildRuntimeGraph(
  rfNodes: Node[],
  rfEdges: Edge[],
  flows: SavedFlow[],
  brokers: Broker[],
  configs: SharedConfig[],
  env: Record<string, string>,
): { nodes: RtNode[]; wires: RtWire[]; env: Record<string, string> } {
  const flowsById = new Map(flows.map((f) => [f.id, f]));
  const top = expandFlow(
    rfNodes,
    rfEdges,
    flowsById,
    brokers,
    configs,
    env,
    "",
    0,
  );
  return { nodes: top.nodes, wires: top.wires, env };
}

// ───────── node renderer (handles depend on type; switch has N outputs) ─────────
function FlowRtNode({ id, data, type, selected }: NodeProps) {
  const t = String(type);
  const c = (data.config ?? {}) as Cfg;
  const count = typeof data.count === "number" ? data.count : 0;
  const status = typeof data.status === "string" ? data.status : "";
  const statusOk = status === "connected";
  if (t === "comment") {
    // a canvas annotation: sticky-note styling, no handles, not deployed
    return (
      <div
        className={`max-w-[240px] whitespace-pre-wrap rounded-sm border-l-4 border-yellow-500 bg-yellow-100/95 px-2.5 py-1.5 text-[11px] leading-snug text-zinc-800 shadow-md shadow-black/40 ${
          selected ? "outline outline-2 outline-amber-400" : ""
        }`}
      >
        {String(c.text ?? "note…")}
      </div>
    );
  }
  if (t === "group") {
    // a container box behind its child nodes (fills the node's style width/height); not deployed
    return (
      <div
        className={`h-full w-full rounded-lg border-2 border-dashed bg-indigo-500/5 ${
          selected ? "border-indigo-400" : "border-indigo-400/40"
        }`}
      >
        <div className="px-2 py-1 text-[11px] font-semibold text-indigo-300/80">
          {String(c.label ?? "group")}
        </div>
      </div>
    );
  }
  const outs =
    t === "switch"
      ? Math.max(1, (c.rules as unknown[])?.length ?? 1)
      : t === "function" || t === "subflow"
        ? Math.max(1, Number(c.outputs ?? 1))
        : t === "debug" ||
            t === "httpresponse" ||
            t === "linkout" ||
            t === "subout"
          ? 0
          : 1;
  const hasIn = !SOURCE_TYPES.includes(t);
  const issue = data.issue as "error" | "warn" | undefined;
  const issueRing =
    issue === "error"
      ? "ring-2 ring-rose-500"
      : issue === "warn"
        ? "ring-2 ring-amber-400"
        : "ring-1 ring-black/40";
  return (
    <div className="relative">
      {hasIn && (
        <Handle
          type="target"
          position={Position.Left}
          id="in"
          className={NIB}
        />
      )}
      <div
        title={
          typeof data.issueMsg === "string"
            ? data.issueMsg
            : c.disabled
              ? "disabled — skipped on deploy"
              : undefined
        }
        className={`min-w-[150px] rounded-md px-3 py-2 text-white shadow-md shadow-black/50 ${issueRing} ${
          NODE_COLOR[t] ?? "bg-zinc-600"
        } ${selected ? "outline outline-2 outline-amber-300" : ""} ${
          c.disabled ? "opacity-40 grayscale" : ""
        }`}
      >
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold">
            {c.disabled ? "⊘ " : ""}
            {t}
          </span>
          {t === "inject" && (
            <button
              type="button"
              title="inject now"
              className="nodrag nopan rounded bg-black/30 px-1.5 text-[11px] leading-4 hover:bg-black/50"
              onClick={async (e) => {
                e.stopPropagation();
                try {
                  // inject only fires through a *running* flow — tell the user when nothing happened
                  const r = await api.injectNow(id);
                  if (r.fired) toast("Injected", "success");
                  else
                    toast(
                      "Nothing fired — deploy the flow first (▸ only works on a running flow).",
                      "error",
                    );
                } catch (err) {
                  toast(`Inject failed: ${(err as Error).message}`, "error");
                }
              }}
            >
              ▸
            </button>
          )}
        </div>
        <div className="truncate text-[10px] text-white/70">
          {summary(t, c)}
        </div>
      </div>
      {count > 0 && (
        <div className="absolute left-1 top-full mt-0.5 flex items-center gap-1 whitespace-nowrap text-[9px] text-zinc-400">
          <span className="h-1.5 w-1.5 rounded-full bg-indigo-400" />
          {count} msg{count === 1 ? "" : "s"}
        </div>
      )}
      {status && (
        <div
          className={`absolute right-1 top-full mt-0.5 flex items-center gap-1 whitespace-nowrap text-[9px] ${
            statusOk ? "text-emerald-400" : "text-amber-400"
          }`}
        >
          <span
            className={`h-1.5 w-1.5 rounded-full ${statusOk ? "bg-emerald-400" : "bg-amber-400"}`}
          />
          {status}
        </div>
      )}
      {Array.from({ length: outs }).map((_, i) => (
        <Handle
          // biome-ignore lint/suspicious/noArrayIndexKey: output ports are positional & stable
          key={i}
          type="source"
          position={Position.Right}
          id={String(i)}
          style={{ top: `${((i + 1) / (outs + 1)) * 100}%` }}
          className={NIB}
        />
      ))}
    </div>
  );
}
// every palette type renders through FlowRtNode — derive so the two never drift
const nodeTypes: Record<string, typeof FlowRtNode> = {
  ...Object.fromEntries(PALETTE.map((p) => [p.type, FlowRtNode])),
  group: FlowRtNode, // created from a selection, not dragged from the palette
  subin: FlowRtNode, // subflow input marker (inside a subflow template)
  subout: FlowRtNode, // subflow output marker
  subflow: FlowRtNode, // a subflow instance placed in a normal flow
};

// Edge with a hover ✕ to delete the wire (routes through deleteElements so undo can snapshot it).
function DeletableEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
}: EdgeProps) {
  const { deleteElements } = useReactFlow();
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });
  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        style={{ stroke: "#9ca3af", strokeWidth: 2, strokeDasharray: "6 4" }}
      />
      <EdgeLabelRenderer>
        {/* wrapper owns the positioning transform; the button's hover:scale-125 then can't fight it */}
        <div
          className="nodrag nopan absolute"
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            pointerEvents: "all",
          }}
        >
          <button
            type="button"
            title="delete wire"
            className="flex h-4 w-4 items-center justify-center rounded-full border border-white/20 bg-zinc-800 text-[10px] leading-none text-zinc-400 opacity-40 transition-transform transition-colors hover:scale-125 hover:border-rose-400 hover:bg-rose-600 hover:text-white hover:opacity-100"
            onClick={() => deleteElements({ edges: [{ id }] })}
          >
            ✕
          </button>
        </div>
      </EdgeLabelRenderer>
    </>
  );
}
// override the built-in "default" edge so every wire (incl. persisted) gets the ✕
const edgeTypes = { default: DeletableEdge };

// Toolbar action icons (24×24 stroke paths) — a cohesive SVG set replacing ad-hoc unicode glyphs.
const TB = {
  play: "M8 5v14l11-7z",
  stop: "M7 7h10v10H7z",
  check: "M5 12l4 4 10-10",
  undo: "M9 7 4 12l5 5M4 12h11a5 5 0 0 1 0 10",
  redo: "M15 7l5 5-5 5M20 12H9a5 5 0 0 0 0 10",
  group: "M4 5h6v6H4zM14 13h6v6h-6zM10 8h4a2 2 0 0 1 2 2v3",
  ungroup:
    "M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4",
  database:
    "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  gear: "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 13a7.6 7.6 0 0 0 0-2l2-1.5-2-3.5-2.3 1a7.5 7.5 0 0 0-1.8-1L14.9 3H9.1l-.4 2.5a7.5 7.5 0 0 0-1.8 1l-2.3-1-2 3.5L4.6 11a7.6 7.6 0 0 0 0 2l-2 1.5 2 3.5 2.3-1a7.5 7.5 0 0 0 1.8 1l.4 2.5h5.8l.4-2.5a7.5 7.5 0 0 0 1.8-1l2.3 1 2-3.5z",
  layers: "M12 3l9 5-9 5-9-5zM3 12l9 5 9-5M3 16l9 5 9-5",
  clock: "M12 8v4l3 2M21 12a9 9 0 1 1-3-6.7L21 7",
  download: "M12 3v12M7 11l5 5 5-5M5 21h14",
  upload: "M12 21V9M7 13l5-5 5 5M5 3h14",
} as const;

const FLOWS_KEY = "chronos-flows-rt";

// ReactFlow requires every node to carry a numeric position; flows restored from a git commit or
// imported from an external source (e.g. a graph authored via the API) may omit it — without this
// guard @xyflow crashes with "Cannot read properties of undefined (reading 'x')" and blanks the canvas.
function ensurePositions(nodes: Node[]): Node[] {
  let auto = 0;
  return (nodes ?? []).map((n) => {
    const p = (n as { position?: { x?: number; y?: number } }).position;
    if (p && Number.isFinite(p.x) && Number.isFinite(p.y)) return n;
    auto += 1;
    return {
      ...n,
      position: {
        x: 80 + ((auto - 1) % 4) * 220,
        y: 80 + Math.floor((auto - 1) / 4) * 120,
      },
    };
  });
}

interface SavedFlow {
  id: string;
  name: string;
  nodes: Node[];
  edges: Edge[];
  subflow?: boolean; // true = a reusable subflow template (inlined at deploy), not a runnable flow
  disabled?: boolean; // true = skipped by "Deploy all / modified" (Node-RED flow enable/disable)
  env?: Record<string, string>; // flow-scoped environment variables (${VAR} / env.VAR)
}
const ENV_KEY = "chronos-flow-env"; // global (app-wide) environment variables
function loadEnv(): Record<string, string> {
  try {
    const o = JSON.parse(localStorage.getItem(ENV_KEY) || "null");
    if (o && typeof o === "object") return o;
  } catch {
    /* fall through */
  }
  return {};
}
function persistEnv(env: Record<string, string>) {
  localStorage.setItem(ENV_KEY, JSON.stringify(env));
}
// Guarantee every flow's nodes carry a position (see ensurePositions) — protects the initial canvas
// render from a position-less node in localStorage (e.g. left there by a git revert / API-authored flow).
function sanitizeFlows(flows: SavedFlow[]): SavedFlow[] {
  return flows.map((f) => ({ ...f, nodes: ensurePositions(f.nodes ?? []) }));
}
function loadFlows(): SavedFlow[] {
  try {
    const arr = JSON.parse(localStorage.getItem(FLOWS_KEY) || "null");
    if (Array.isArray(arr) && arr.length) return sanitizeFlows(arr);
  } catch {
    /* fall through */
  }
  // migrate the old single-graph store, if present
  try {
    const old = JSON.parse(localStorage.getItem("chronos-flowgraph") || "null");
    if (old?.nodes) {
      return [
        {
          id: "flow-1",
          name: "Flow 1",
          nodes: old.nodes,
          edges: old.edges ?? [],
        },
      ];
    }
  } catch {
    /* fall through */
  }
  return [{ id: "flow-1", name: "Flow 1", nodes: [], edges: [] }];
}
function persistFlows(flows: SavedFlow[]) {
  localStorage.setItem(FLOWS_KEY, JSON.stringify(flows));
}

// Shared MQTT broker config (Node-RED config-node concept): defined once, referenced by mqtt nodes.
interface Broker {
  id: string;
  name: string;
  brokerUrl: string;
  username?: string;
  password?: string;
}
const BROKERS_KEY = "chronos-flow-brokers";
function loadBrokers(): Broker[] {
  try {
    const arr = JSON.parse(localStorage.getItem(BROKERS_KEY) || "null");
    if (Array.isArray(arr)) return arr;
  } catch {
    /* fall through */
  }
  return [
    { id: "b-local", name: "mosquitto", brokerUrl: "tcp://mosquitto:1883" },
  ];
}
function persistBrokers(b: Broker[]) {
  localStorage.setItem(BROKERS_KEY, JSON.stringify(b));
}

// generic shared config node: a named bag of key/values any node can reference (configRef); merged
// into the node's config at deploy (generalizes the MQTT broker config-node pattern).
interface SharedConfig {
  id: string;
  name: string;
  props: Record<string, string>;
}
const CONFIGS_KEY = "chronos-flow-configs";
function loadConfigs(): SharedConfig[] {
  try {
    const arr = JSON.parse(localStorage.getItem(CONFIGS_KEY) || "null");
    if (Array.isArray(arr)) return arr;
  } catch {
    /* fall through */
  }
  return [];
}
function persistConfigs(c: SharedConfig[]) {
  localStorage.setItem(CONFIGS_KEY, JSON.stringify(c));
}

function FlowTab({
  active,
  running,
  disabled,
  onClick,
  onDelete,
  children,
}: {
  active: boolean;
  running?: boolean;
  disabled?: boolean;
  onClick: () => void;
  onDelete?: () => void;
  children: React.ReactNode;
}) {
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 border-b-2 px-3 py-1.5 ${
        active
          ? "border-indigo-400 text-zinc-100"
          : "border-transparent text-zinc-400 hover:text-zinc-200"
      } ${disabled ? "opacity-50" : ""}`}
    >
      <button
        type="button"
        onClick={onClick}
        className="flex items-center gap-1.5"
      >
        {running && (
          <span
            title="running"
            className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-emerald-500"
          />
        )}
        {children}
      </button>
      {onDelete && (
        <button
          type="button"
          onClick={onDelete}
          aria-label="delete flow"
          className="text-zinc-600 hover:text-rose-400"
        >
          ✕
        </button>
      )}
    </span>
  );
}

function FlowEditor() {
  const rf = useReactFlow();
  const initial = useRef(loadFlows()).current;
  const [flows, setFlows] = useState<SavedFlow[]>(initial);
  const [activeId, setActiveId] = useState<string>(initial[0].id);
  const loadedFor = useRef<string>(initial[0].id); // which flow is on the canvas
  const pendingLoad = useRef(false); // skip the stale save right after a tab switch
  const [rfNodes, setNodes, onNodesChange] = useNodesState<Node>(
    initial[0].nodes,
  );
  const [rfEdges, setEdges, onEdgesChange] = useEdgesState<Edge>(
    initial[0].edges,
  );
  const [editId, setEditId] = useState<string | null>(null);
  // ids of flows currently running in the backend (many run at once); the active flow's state derives
  const [runningIds, setRunningIds] = useState<Set<string>>(() => new Set());
  const running = runningIds.has(activeId);
  const [status, setStatus] = useState("");
  const [deployMode, setDeployMode] = useState<"this" | "all" | "modified">(
    "this",
  );
  const deployedGraph = useRef<Map<string, string>>(new Map()); // flowId → last-deployed graph JSON
  const [brokers, setBrokers] = useState<Broker[]>(loadBrokers);
  const [brokersOpen, setBrokersOpen] = useState(false);
  const [globalEnv, setGlobalEnv] = useState<Record<string, string>>(loadEnv);
  const [envOpen, setEnvOpen] = useState(false);
  const [configs, setConfigs] = useState<SharedConfig[]>(loadConfigs);
  const [configsOpen, setConfigsOpen] = useState(false);
  const [projectsOpen, setProjectsOpen] = useState(false);
  const [newFlowOpen, setNewFlowOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState("");
  // collapsed palette categories (Node-RED-style) — a search query force-expands all
  const [collapsedCats, setCollapsedCats] = useState<Set<string>>(new Set());
  const toggleCat = (id: string) =>
    setCollapsedCats((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  const lastSeq = useRef(0);
  const fileRef = useRef<HTMLInputElement>(null);
  const clipboard = useRef<{ nodes: Node[]; edges: Edge[] } | null>(null);
  const [metrics, setMetrics] = useState<FlowMetrics | null>(null);
  const [rate, setRate] = useState(0); // msgs/sec, derived from totalMessages deltas
  const prevMsg = useRef<{ n: number; t: number } | null>(null);
  // server-side per-user flow library (multi-user flows); localStorage is the offline mirror
  const [cloud, setCloud] = useState<
    "loading" | "synced" | "offline" | "readonly"
  >("loading");
  const serverReady = useRef(false); // server load finished — ok to push edits up
  const saveTimer = useRef<number | null>(null);

  // undo/redo: snapshots of {nodes, edges} taken before each structural change
  type Snap = { nodes: Node[]; edges: Edge[] };
  const [past, setPast] = useState<Snap[]>([]);
  const [future, setFuture] = useState<Snap[]>([]);
  const snapshot = () => {
    setPast((p) => [...p, { nodes: rfNodes, edges: rfEdges }].slice(-50));
    setFuture([]);
  };
  const undo = () => {
    setPast((p) => {
      if (p.length === 0) return p;
      setFuture((f) => [{ nodes: rfNodes, edges: rfEdges }, ...f]);
      setNodes(p[p.length - 1].nodes);
      setEdges(p[p.length - 1].edges);
      return p.slice(0, -1);
    });
  };
  const redo = () => {
    setFuture((f) => {
      if (f.length === 0) return f;
      setPast((p) => [...p, { nodes: rfNodes, edges: rfEdges }]);
      setNodes(f[0].nodes);
      setEdges(f[0].edges);
      return f.slice(1);
    });
  };
  // copy selected nodes (+ internal edges) to an in-memory clipboard
  const copySelection = () => {
    const sel = rfNodes.filter((n) => n.selected && n.type !== "group");
    if (sel.length === 0) return false;
    const ids = new Set(sel.map((n) => n.id));
    const edges = rfEdges.filter((e) => ids.has(e.source) && ids.has(e.target));
    clipboard.current = { nodes: sel, edges };
    return true;
  };
  // paste the clipboard with fresh ids, offset, and selected; returns true if it pasted
  const pasteClipboard = () => {
    const clip = clipboard.current;
    if (!clip || clip.nodes.length === 0) return false;
    snapshot();
    const idMap = new Map<string, string>();
    const newNodes = clip.nodes.map((n) => {
      const nid = `${n.type}-${crypto.randomUUID().slice(0, 8)}`;
      idMap.set(n.id, nid);
      // paste as a top-level node (drop any group membership to avoid dangling parents)
      const { parentId, extent, ...base } = n;
      return {
        ...base,
        id: nid,
        position: { x: n.position.x + 40, y: n.position.y + 40 },
        selected: true,
        // deep-clone the config so the copy isn't shared with the original
        data: { config: JSON.parse(JSON.stringify(n.data.config ?? {})) },
      };
    });
    const newEdges = clip.edges.map((e) => ({
      ...e,
      id: `e-${crypto.randomUUID()}`,
      source: idMap.get(e.source) ?? e.source,
      target: idMap.get(e.target) ?? e.target,
      selected: false,
    }));
    setNodes((nds) => [
      ...nds.map((n) => (n.selected ? { ...n, selected: false } : n)),
      ...newNodes,
    ]);
    setEdges((eds) => [...eds, ...newEdges]);
    return true;
  };

  // wrap the selected top-level nodes in a group container (children move with the group)
  const groupSelection = () => {
    const sel = rfNodes.filter(
      (n) => n.selected && n.type !== "group" && !n.parentId,
    );
    if (sel.length === 0) return;
    snapshot();
    const dim = (n: Node) => ({
      w: n.measured?.width ?? n.width ?? 160,
      h: n.measured?.height ?? n.height ?? 60,
    });
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const n of sel) {
      const { w, h } = dim(n);
      minX = Math.min(minX, n.position.x);
      minY = Math.min(minY, n.position.y);
      maxX = Math.max(maxX, n.position.x + w);
      maxY = Math.max(maxY, n.position.y + h);
    }
    const pad = 26;
    const labelH = 22;
    const gx = minX - pad;
    const gy = minY - pad - labelH;
    const gid = `group-${crypto.randomUUID()}`;
    const group: Node = {
      id: gid,
      type: "group",
      position: { x: gx, y: gy },
      data: { config: { label: "group" } },
      style: {
        width: maxX - minX + pad * 2,
        height: maxY - minY + pad * 2 + labelH,
      },
      selected: false,
    };
    const selIds = new Set(sel.map((n) => n.id));
    setNodes((nds) => [
      group, // parent must come before its children in the array
      ...nds.map((n) =>
        selIds.has(n.id)
          ? {
              ...n,
              parentId: gid,
              extent: "parent" as const,
              position: { x: n.position.x - gx, y: n.position.y - gy },
              selected: false,
            }
          : n,
      ),
    ]);
  };

  // dissolve the selected group(s): children return to absolute positions, group box removed
  const ungroupSelection = () => {
    const groups = rfNodes.filter((n) => n.selected && n.type === "group");
    if (groups.length === 0) return;
    snapshot();
    const gpos = new Map(groups.map((g) => [g.id, g.position]));
    setNodes((nds) =>
      nds
        .filter((n) => !gpos.has(n.id))
        .map((n) => {
          const gp = n.parentId ? gpos.get(n.parentId) : undefined;
          if (!gp) return n;
          const { parentId, extent, ...rest } = n;
          return {
            ...rest,
            position: { x: n.position.x + gp.x, y: n.position.y + gp.y },
          };
        }),
    );
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      const el = document.activeElement;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA")) return;
      const k = e.key.toLowerCase();
      if (k === "z") {
        e.preventDefault();
        if (e.shiftKey) redo();
        else undo();
      } else if (k === "c") {
        if (copySelection()) e.preventDefault();
      } else if (k === "v") {
        if (pasteClipboard()) e.preventDefault();
      } else if (k === "d") {
        // duplicate = copy + paste in one gesture
        if (copySelection()) {
          e.preventDefault();
          pasteClipboard();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // load the active flow's graph onto the canvas when the tab changes
  useEffect(() => {
    if (loadedFor.current === activeId) return;
    const f = flows.find((x) => x.id === activeId);
    pendingLoad.current = true;
    setNodes(ensurePositions(f?.nodes ?? []));
    setEdges(f?.edges ?? []);
    setPast([]);
    setFuture([]);
    loadedFor.current = activeId;
  }, [activeId, flows, setNodes, setEdges]);

  // on mount: load this user's flows from the server; if the server is empty, seed it from the
  // local mirror (first-run migration). On error, fall back to localStorage (offline).
  // biome-ignore lint/correctness/useExhaustiveDependencies: run once on mount
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const defs = await api.listFlowDefs();
        if (cancelled) return;
        if (defs.length > 0) {
          const loaded: SavedFlow[] = sanitizeFlows(
            defs.map((d) => ({
              id: d.id,
              name: d.name,
              nodes: (d.graph?.nodes ?? []) as Node[],
              edges: (d.graph?.edges ?? []) as Edge[],
              subflow: (d.graph as { subflow?: boolean })?.subflow,
              disabled: (d.graph as { disabled?: boolean })?.disabled,
              env: (d.graph as { env?: Record<string, string> })?.env,
            })),
          );
          setFlows(loaded);
          persistFlows(loaded);
          loadedFor.current = ""; // force the load effect to swap the canvas
          setActiveId(loaded[0].id);
        } else {
          for (const f of initial) {
            await api
              .saveFlowDef(f.id, {
                name: f.name,
                graph: {
                  nodes: f.nodes,
                  edges: f.edges,
                  subflow: f.subflow,
                  disabled: f.disabled,
                  env: f.env,
                },
              })
              .catch(() => {});
          }
        }
        serverReady.current = true;
        setCloud("synced");
      } catch {
        serverReady.current = true; // allow local-only operation
        setCloud("offline");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Re-pull the whole flow library from the server (used after a Projects revert).
  async function reloadFlows() {
    const defs = await api.listFlowDefs();
    if (defs.length === 0) return;
    const loaded: SavedFlow[] = sanitizeFlows(
      defs.map((d) => ({
        id: d.id,
        name: d.name,
        nodes: (d.graph?.nodes ?? []) as Node[],
        edges: (d.graph?.edges ?? []) as Edge[],
        subflow: (d.graph as { subflow?: boolean })?.subflow,
        disabled: (d.graph as { disabled?: boolean })?.disabled,
        env: (d.graph as { env?: Record<string, string> })?.env,
      })),
    );
    setFlows(loaded);
    persistFlows(loaded);
    loadedFor.current = ""; // force the load effect to swap the canvas
    setActiveId(loaded[0].id);
  }

  // debounced push of one flow to the server (no-op until the initial load settles)
  function queueServerSave(f: SavedFlow) {
    if (!serverReady.current) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      api
        .saveFlowDef(f.id, {
          name: f.name,
          graph: {
            nodes: f.nodes,
            edges: f.edges,
            subflow: f.subflow,
            disabled: f.disabled,
            env: f.env,
          },
        })
        .then(() => setCloud("synced"))
        .catch((e: Error) =>
          setCloud(String(e.message).includes("403") ? "readonly" : "offline"),
        );
    }, 800);
  }

  // persist canvas edits back to the active flow (config + topology only — strip live counts)
  // biome-ignore lint/correctness/useExhaustiveDependencies: fire on graph/active change only
  useEffect(() => {
    if (loadedFor.current !== activeId) return;
    if (pendingLoad.current) {
      pendingLoad.current = false; // skip the first fire right after a tab switch
      return;
    }
    // onNodesChange fires ~per frame while dragging — don't serialize the whole library each frame;
    // the drag-end change (dragging=false) runs this effect once more and persists the final positions.
    if (rfNodes.some((n) => n.dragging)) return;
    const clean = rfNodes.map((n) => ({
      ...n,
      data: { config: n.data.config },
    }));
    setFlows((prev) => {
      const next = prev.map((f) =>
        f.id === activeId ? { ...f, nodes: clean, edges: rfEdges } : f,
      );
      persistFlows(next);
      const active = next.find((f) => f.id === activeId);
      if (active) queueServerSave(active); // mirror the edit to the server (debounced)
      return next;
    });
  }, [rfNodes, rfEdges, activeId]);

  // poll which flows are running (drives per-tab indicators + the active flow's running state)
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const { flows } = await api.listRunningFlows();
        if (alive) setRunningIds(new Set(flows.map((f) => f.flowId)));
      } catch {
        /* transient */
      }
    };
    const id = setInterval(tick, 2500);
    tick();
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, []);

  // tell the Inspector which flow is active (scopes its Context tab); reset the debug feed on switch
  useEffect(() => {
    publishActiveFlow(activeId);
    clearDebug();
    lastSeq.current = 0;
  }, [activeId]);

  // publish the single selected node to the Inspector Info tab (type + help + settings)
  const selectedSig = rfNodes
    .filter((n) => n.selected)
    .map((n) => n.id)
    .join(",");
  // biome-ignore lint/correctness/useExhaustiveDependencies: republish only when the selection changes
  useEffect(() => {
    const sel = rfNodes.filter((n) => n.selected);
    if (sel.length !== 1) {
      publishSelection(null);
      return;
    }
    const n = sel[0];
    const t = String(n.type);
    const c = (n.data.config ?? {}) as Cfg;
    publishSelection({
      type: t,
      title: t === "group" ? String(c.label ?? "group") : summary(t, c),
      help: NODE_HELP[t],
      fields: Object.entries(c).map(([k, v]) => [
        k,
        typeof v === "object" ? JSON.stringify(v) : String(v),
      ]),
    });
  }, [selectedSig]);

  // while the ACTIVE flow runs: stream its debug → Inspector, overlay per-node counts, update monitor
  useEffect(() => {
    if (!running) {
      setMetrics(null);
      setRate(0);
      prevMsg.current = null;
      setNodes((nds) =>
        nds.some((n) => n.data.count || n.data.status)
          ? nds.map((n) =>
              n.data.count || n.data.status
                ? { ...n, data: { ...n.data, count: 0, status: undefined } }
                : n,
            )
          : nds,
      );
      return;
    }
    let alive = true;
    const tick = async () => {
      try {
        const recs = await api.flowDebug(activeId, lastSeq.current);
        if (!alive) return; // tab switched / unmounted mid-request — don't apply stale data
        for (const r of [...recs].reverse()) {
          // oldest→newest
          lastSeq.current = Math.max(lastSeq.current, r.seq);
          publishDebug({
            node: r.name,
            topic: String(r.topic ?? r.nodeId),
            value: r.payload,
          });
        }
        const st = await api.flowStatus(activeId);
        if (!alive) return;
        const counts = st.counts ?? {};
        const statuses = st.statuses ?? {};
        setNodes((nds) =>
          nds.map((n) => {
            const cnt = counts[n.id] ?? 0;
            const stat = statuses[n.id];
            return n.data.count === cnt && n.data.status === stat
              ? n
              : { ...n, data: { ...n.data, count: cnt, status: stat } };
          }),
        );
        const m = await api.flowMetrics(activeId);
        if (!alive) return;
        setMetrics(m);
        const now = Date.now();
        if (prevMsg.current) {
          const dt = (now - prevMsg.current.t) / 1000;
          if (dt > 0) {
            setRate(Math.max(0, (m.totalMessages - prevMsg.current.n) / dt));
          }
        }
        prevMsg.current = { n: m.totalMessages, t: now };
      } catch {
        /* transient */
      }
    };
    const id = setInterval(tick, 1500);
    tick();
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [running, activeId, setNodes]);

  const onConnect = (c: Connection) => {
    snapshot();
    setEdges((eds) => addEdge({ ...c }, eds));
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    const type = e.dataTransfer.getData("application/chronos-flow");
    if (!type) return;
    snapshot();
    const position = rf.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    // "subflow:<id>" drops a subflow instance referencing that template
    if (type.startsWith("subflow:")) {
      const ref = type.slice("subflow:".length);
      const def = flows.find((f) => f.id === ref);
      const outputs = Math.max(
        1,
        def?.nodes.filter((n) => n.type === "subout").length ?? 1,
      );
      const id = `subflow-${crypto.randomUUID().slice(0, 8)}`;
      setNodes((nds) => [
        ...nds,
        {
          id,
          type: "subflow",
          position,
          data: { config: { ref, name: def?.name ?? "subflow", outputs } },
        },
      ]);
      return;
    }
    const id = `${type}-${crypto.randomUUID().slice(0, 8)}`;
    setNodes((nds) => [
      ...nds,
      { id, type, position, data: { config: defaultConfig(type) } },
    ]);
    setEditId(id);
  };

  // paint validation issues onto the canvas (red ring = error, amber = warning) + clear stale ones
  function applyIssues(issues: Issue[]) {
    const flagged = new Map<string, Issue>();
    for (const i of issues) {
      if (!i.nodeId) continue;
      const existing = flagged.get(i.nodeId);
      // an error outranks a warning on the same node
      if (!existing || (existing.level === "warn" && i.level === "error"))
        flagged.set(i.nodeId, i);
    }
    setNodes((nds) =>
      nds.map((n) => {
        const f = flagged.get(n.id);
        const lvl = f?.level;
        const msg = f?.message;
        if (n.data.issue === lvl && n.data.issueMsg === msg) return n;
        return { ...n, data: { ...n.data, issue: lvl, issueMsg: msg } };
      }),
    );
  }

  // run validation only (no deploy) — for the Check button
  function checkFlow() {
    const issues = validateGraph(rfNodes, rfEdges, brokers);
    applyIssues(issues);
    const errs = issues.filter((i) => i.level === "error");
    const warns = issues.filter((i) => i.level === "warn");
    if (errs.length === 0 && warns.length === 0) {
      toast("Flow looks good — no issues.", "success");
    } else {
      if (errs.length)
        toast(`${errs.length} error(s): ${errs[0].message}`, "error");
      if (warns.length)
        toast(`${warns.length} warning(s): ${warns[0].message}`, "info");
    }
  }

  // build a flow's runtime graph — the active flow uses the live canvas, others use their saved graph
  function graphFor(f: SavedFlow) {
    const nodes = f.id === activeId ? rfNodes : f.nodes;
    const edges = f.id === activeId ? rfEdges : f.edges;
    const mergedEnv = { ...globalEnv, ...(f.env ?? {}) };
    return buildRuntimeGraph(nodes, edges, flows, brokers, configs, mergedEnv);
  }

  async function run() {
    if (deployMode !== "this") {
      return deployMany();
    }
    // subflow templates are inlined into flows that use them — not deployed standalone
    if (flows.find((f) => f.id === activeId)?.subflow) {
      toast("This is a subflow template — deploy a flow that uses it.", "info");
      return;
    }
    // validate first — block deploy on errors, allow (but warn) on warnings
    const issues = validateGraph(rfNodes, rfEdges, brokers);
    applyIssues(issues);
    const errs = issues.filter((i) => i.level === "error");
    if (errs.length) {
      toast(
        `Cannot deploy — ${errs.length} error(s): ${errs[0].message}`,
        "error",
      );
      setStatus(`validation failed — ${errs.length} error(s)`);
      return;
    }
    const warns = issues.filter((i) => i.level === "warn");
    if (warns.length)
      toast(`Deploying with ${warns.length} warning(s).`, "info");
    // deploy only the active flow into its own runtime; other running flows are unaffected
    setStatus("deploying…");
    try {
      const activeFlow = flows.find((f) => f.id === activeId);
      if (!activeFlow) return;
      const graph = graphFor(activeFlow);
      const r = await api.runFlow(graph, activeId, activeFlow.name);
      deployedGraph.current.set(activeId, JSON.stringify(graph));
      lastSeq.current = 0;
      setRunningIds((s) => new Set(s).add(activeId));
      applyIssues([]); // clear any validation rings now that it deployed
      setStatus(`running · ${r.nodes} nodes — open Debug (top-right) to watch`);
      toast(`Deployed · ${r.nodes} nodes running`, "success");
    } catch (err) {
      toast(`Deploy failed: ${(err as Error).message}`, "error");
      setStatus(`error: ${(err as Error).message}`);
    }
  }

  // Deploy all (or only modified) enabled, non-subflow flows — Node-RED's full/modified deploy modes.
  async function deployMany() {
    const candidates = flows.filter((f) => !f.subflow && !f.disabled);
    const graphs = candidates.map((f) => ({ f, g: graphFor(f) }));
    const targets =
      deployMode === "modified"
        ? graphs.filter(
            ({ f, g }) => deployedGraph.current.get(f.id) !== JSON.stringify(g),
          )
        : graphs;
    if (targets.length === 0) {
      toast("Nothing to deploy — all flows already up to date.", "info");
      return;
    }
    setStatus("deploying…");
    try {
      let nodes = 0;
      const next = new Set(runningIds);
      for (const { f, g } of targets) {
        const r = await api.runFlow(g, f.id, f.name);
        deployedGraph.current.set(f.id, JSON.stringify(g));
        next.add(f.id);
        nodes += r.nodes;
      }
      setRunningIds(next);
      lastSeq.current = 0;
      applyIssues([]);
      const label = deployMode === "modified" ? "modified" : "all";
      setStatus(`deployed ${targets.length} ${label} flow(s) · ${nodes} nodes`);
      toast(`Deployed ${targets.length} flow(s) · ${nodes} nodes`, "success");
    } catch (err) {
      toast(`Deploy failed: ${(err as Error).message}`, "error");
      setStatus(`error: ${(err as Error).message}`);
    }
  }

  // toggle the active flow's enabled/disabled state (excluded from deploy all/modified when disabled)
  function toggleFlowDisabled() {
    const active = flows.find((f) => f.id === activeId);
    if (!active) return;
    const updated = { ...active, disabled: !active.disabled };
    const next = flows.map((f) => (f.id === activeId ? updated : f));
    setFlows(next);
    persistFlows(next);
    queueServerSave(updated);
    toast(updated.disabled ? "Flow disabled" : "Flow enabled", "info");
  }

  async function stop() {
    try {
      await api.stopFlow(activeId);
    } catch {
      /* ignore */
    }
    setRunningIds((s) => {
      const n = new Set(s);
      n.delete(activeId);
      return n;
    });
    setStatus("stopped");
    setMetrics(null);
    setRate(0);
    prevMsg.current = null;
    applyIssues([]); // clear validation rings
    setNodes((nds) =>
      nds.map((n) =>
        n.data.count ? { ...n, data: { ...n.data, count: 0 } } : n,
      ),
    );
  }

  function createFlow(name: string) {
    const id = `flow-${crypto.randomUUID()}`;
    const fresh: SavedFlow = { id, name, nodes: [], edges: [] };
    const next = [...flows, fresh];
    setFlows(next);
    persistFlows(next);
    queueServerSave(fresh); // create it server-side too
    setActiveId(id); // load effect swaps the canvas to the new empty flow
  }
  // a subflow template starts with an input + output marker; wire nodes between them
  function createSubflow() {
    const id = `flow-${crypto.randomUUID()}`;
    const name = `Subflow ${flows.filter((f) => f.subflow).length + 1}`;
    const fresh: SavedFlow = {
      id,
      name,
      subflow: true,
      nodes: [
        {
          id: "subin",
          type: "subin",
          position: { x: 80, y: 140 },
          data: { config: {} },
        },
        {
          id: "subout",
          type: "subout",
          position: { x: 560, y: 140 },
          data: { config: {} },
        },
      ],
      edges: [],
    };
    const next = [...flows, fresh];
    setFlows(next);
    persistFlows(next);
    queueServerSave(fresh);
    setActiveId(id);
  }
  function deleteFlow(id: string) {
    if (flows.length <= 1) return; // always keep one flow
    const next = flows.filter((f) => f.id !== id);
    setFlows(next);
    persistFlows(next);
    if (runningIds.has(id)) api.stopFlow(id).catch(() => {}); // stop its runtime too
    if (serverReady.current) api.deleteFlowDef(id).catch(() => {});
    if (activeId === id) setActiveId(next[0].id);
  }

  // export the active flow as a downloadable JSON file (Node-RED-style portable graph)
  function exportFlow() {
    const active = flows.find((f) => f.id === activeId);
    const clean = rfNodes.map((n) => ({
      id: n.id,
      type: n.type,
      position: n.position,
      parentId: n.parentId, // preserve group membership + box across export/import
      extent: n.extent,
      style: n.style,
      data: { config: n.data.config },
    }));
    const doc = {
      chronosFlow: 1,
      name: active?.name ?? "flow",
      nodes: clean,
      edges: rfEdges,
    };
    const blob = new Blob([JSON.stringify(doc, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${(active?.name ?? "flow").replace(/[^\w.-]+/g, "_")}.flow.json`;
    a.click();
    URL.revokeObjectURL(url);
    toast(`Exported "${active?.name}"`, "success");
  }

  // import a previously-exported flow as a new tab
  function importFlow(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ""; // allow re-importing the same file
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const doc = JSON.parse(String(reader.result));
        if (!Array.isArray(doc.nodes)) throw new Error("not a flow file");
        const id = `flow-${crypto.randomUUID()}`;
        const imported: SavedFlow = {
          id,
          name: typeof doc.name === "string" ? doc.name : "Imported",
          nodes: doc.nodes,
          edges: Array.isArray(doc.edges) ? doc.edges : [],
        };
        const next = [...flows, imported];
        setFlows(next);
        persistFlows(next);
        queueServerSave(imported); // persist the import server-side too
        setActiveId(id); // switch to the imported flow
        toast(`Imported "${imported.name}"`, "success");
      } catch (err) {
        toast(`Import failed: ${(err as Error).message}`, "error");
      }
    };
    reader.readAsText(file);
  }

  const editNode = rfNodes.find((n) => n.id === editId);
  const update = (cfg: Cfg) =>
    setNodes((nds) =>
      nds.map((n) => (n.id === editId ? { ...n, data: { config: cfg } } : n)),
    );

  return (
    <div className="flex h-full min-h-0 gap-3">
      {/* palette */}
      <div className="flex w-40 shrink-0 flex-col gap-1 overflow-auto rounded-xl border border-white/5 bg-zinc-900/60 p-3 text-xs">
        <input
          className={`${inputClass} mb-1 w-full`}
          placeholder="search nodes…"
          value={paletteQuery}
          onChange={(e) => setPaletteQuery(e.target.value)}
        />
        {CATEGORIES.map((cat) => {
          const q = paletteQuery.trim().toLowerCase();
          const items = PALETTE.filter(
            (p) =>
              p.cat === cat.id &&
              (!q ||
                p.label.toLowerCase().includes(q) ||
                p.type.toLowerCase().includes(q)),
          );
          if (items.length === 0) return null;
          const collapsed = !q && collapsedCats.has(cat.id); // a search always expands
          return (
            <div key={cat.id}>
              <button
                type="button"
                onClick={() => toggleCat(cat.id)}
                className="mb-0.5 mt-1.5 flex w-full items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-zinc-500 hover:text-zinc-300"
              >
                <span className="text-zinc-600">{collapsed ? "▸" : "▾"}</span>
                {cat.label}
                <span className="ml-auto text-zinc-600">{items.length}</span>
              </button>
              {!collapsed &&
                items.map((p) => (
                  <button
                    key={p.type}
                    type="button"
                    draggable
                    onDragStart={(e) => {
                      e.dataTransfer.setData(
                        "application/chronos-flow",
                        p.type,
                      );
                      e.dataTransfer.effectAllowed = "move";
                    }}
                    className="mb-1 flex w-full cursor-grab items-center gap-2 rounded-md border border-white/10 bg-zinc-800 px-2 py-1.5 text-left hover:border-white/20 active:cursor-grabbing"
                  >
                    <span
                      className={`h-2.5 w-2.5 shrink-0 rounded-full ${NODE_COLOR[p.type]}`}
                    />
                    {p.label}
                  </button>
                ))}
            </div>
          );
        })}
        {flows.find((f) => f.id === activeId)?.subflow &&
          [
            { type: "subin", label: "◦ subflow input" },
            { type: "subout", label: "subflow output ◦" },
          ].map((p) => (
            <button
              key={p.type}
              type="button"
              draggable
              onDragStart={(e) => {
                e.dataTransfer.setData("application/chronos-flow", p.type);
                e.dataTransfer.effectAllowed = "move";
              }}
              className="flex cursor-grab items-center gap-2 rounded-md border border-violet-500/30 bg-violet-900/30 px-2 py-1.5 text-left hover:border-violet-400/50 active:cursor-grabbing"
            >
              <span className="h-2.5 w-2.5 rounded-full bg-violet-700/90" />
              {p.label}
            </button>
          ))}
        {flows.some(
          (f) =>
            f.subflow &&
            (!paletteQuery.trim() ||
              f.name.toLowerCase().includes(paletteQuery.trim().toLowerCase())),
        ) && (
          <>
            <div className="mt-2 mb-1 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
              Subflows
            </div>
            {flows
              .filter(
                (f) =>
                  f.subflow &&
                  (!paletteQuery.trim() ||
                    f.name
                      .toLowerCase()
                      .includes(paletteQuery.trim().toLowerCase())),
              )
              .map((f) => (
                <button
                  key={f.id}
                  type="button"
                  draggable
                  onDragStart={(e) => {
                    e.dataTransfer.setData(
                      "application/chronos-flow",
                      `subflow:${f.id}`,
                    );
                    e.dataTransfer.effectAllowed = "move";
                  }}
                  className="flex cursor-grab items-center gap-2 rounded-md border border-violet-500/30 bg-violet-900/30 px-2 py-1.5 text-left hover:border-violet-400/50 active:cursor-grabbing"
                >
                  <span className="h-2.5 w-2.5 rounded-full bg-violet-600/90" />
                  {f.name}
                </button>
              ))}
          </>
        )}
        <p className="mt-auto pt-3 text-[10px] leading-relaxed text-zinc-600">
          Drag nodes, wire them, then Deploy. Inject triggers; messages flow →
          Debug node prints to the Inspector Debug tab.
        </p>
      </div>

      {/* canvas */}
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        {/* flow tabs */}
        <div className="flex items-center gap-1 overflow-x-auto border-b border-white/5 text-xs">
          {flows.map((f) => (
            <FlowTab
              key={f.id}
              active={f.id === activeId}
              running={runningIds.has(f.id)}
              disabled={f.disabled}
              onClick={() => setActiveId(f.id)}
              onDelete={flows.length > 1 ? () => deleteFlow(f.id) : undefined}
            >
              {f.disabled ? "⊘ " : ""}
              {f.subflow ? `⋔ ${f.name}` : f.name}
            </FlowTab>
          ))}
          <button
            type="button"
            onClick={() => setNewFlowOpen(true)}
            className="ml-1 rounded-md px-2 py-1.5 text-zinc-400 hover:bg-white/5 hover:text-zinc-200"
          >
            + flow
          </button>
          <button
            type="button"
            onClick={createSubflow}
            className="rounded-md px-2 py-1.5 text-violet-400 hover:bg-white/5 hover:text-violet-300"
          >
            + subflow
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2 gap-y-2 text-sm">
          <Button onClick={run}>
            <Icon path={TB.play} />{" "}
            {deployMode === "this"
              ? "Deploy & run"
              : deployMode === "all"
                ? "Deploy all"
                : "Deploy modified"}
          </Button>
          <select
            aria-label="deploy mode"
            className={`${inputClass} py-1`}
            value={deployMode}
            onChange={(e) =>
              setDeployMode(e.target.value as "this" | "all" | "modified")
            }
          >
            <option value="this">this flow</option>
            <option value="all">all flows</option>
            <option value="modified">modified flows</option>
          </select>
          <Button variant="ghost" onClick={stop} disabled={!running}>
            <Icon path={TB.stop} /> Stop
          </Button>
          <Button variant="ghost" onClick={checkFlow}>
            <Icon path={TB.check} /> Check
          </Button>
          <Button variant="ghost" onClick={undo} disabled={past.length === 0}>
            <Icon path={TB.undo} /> Undo
          </Button>
          <Button variant="ghost" onClick={redo} disabled={future.length === 0}>
            <Icon path={TB.redo} /> Redo
          </Button>
          <Button
            variant="ghost"
            onClick={groupSelection}
            disabled={
              !rfNodes.some(
                (n) => n.selected && n.type !== "group" && !n.parentId,
              )
            }
          >
            <Icon path={TB.group} /> Group
          </Button>
          <Button
            variant="ghost"
            onClick={ungroupSelection}
            disabled={!rfNodes.some((n) => n.selected && n.type === "group")}
          >
            <Icon path={TB.ungroup} /> Ungroup
          </Button>
          <Button variant="ghost" onClick={() => setBrokersOpen(true)}>
            <Icon path={TB.database} /> Brokers
          </Button>
          <Button variant="ghost" onClick={() => setEnvOpen(true)}>
            <Icon path={TB.gear} /> Env
          </Button>
          <Button variant="ghost" onClick={() => setConfigsOpen(true)}>
            <Icon path={TB.layers} /> Configs
          </Button>
          <Button variant="ghost" onClick={() => setProjectsOpen(true)}>
            <Icon path={TB.clock} /> History
          </Button>
          <Button variant="ghost" onClick={toggleFlowDisabled}>
            {flows.find((f) => f.id === activeId)?.disabled
              ? "◉ Enable flow"
              : "⊘ Disable flow"}
          </Button>
          <Button variant="ghost" onClick={exportFlow}>
            <Icon path={TB.download} /> Export
          </Button>
          <Button variant="ghost" onClick={() => fileRef.current?.click()}>
            <Icon path={TB.upload} /> Import
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={importFlow}
          />
          <span
            className={`ml-2 flex items-center gap-1.5 text-xs ${running ? "text-emerald-400" : "text-zinc-500"}`}
          >
            <span
              className={`h-2 w-2 rounded-full ${running ? "animate-pulse bg-emerald-500" : "bg-zinc-600"}`}
            />
            {status || "idle"}
          </span>
          <span
            className="text-xs"
            title={
              cloud === "synced"
                ? "flows saved to your server library"
                : cloud === "readonly"
                  ? "viewer/operator role — flows load from server but edits stay local (admin-only writes)"
                  : cloud === "offline"
                    ? "server unreachable — using local copy"
                    : "loading server flows…"
            }
          >
            {cloud === "synced"
              ? "☁ synced"
              : cloud === "readonly"
                ? "☁ read-only"
                : cloud === "offline"
                  ? "⚠ offline"
                  : "☁ …"}
          </span>
        </div>
        {metrics && (
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-white/5 bg-zinc-900/60 px-3 py-1.5 text-[11px]">
            <span className="font-semibold uppercase tracking-wider text-zinc-500">
              Monitor
            </span>
            {metrics.deployName && (
              <span className="flex items-baseline gap-1">
                <span className="text-zinc-500">flow</span>
                <span className="font-semibold text-indigo-300">
                  {metrics.deployName}
                </span>
                {metrics.deployBy && (
                  <span className="text-zinc-500">by {metrics.deployBy}</span>
                )}
              </span>
            )}
            <Metric label="msg/s" value={rate.toFixed(1)} tone="emerald" />
            <Metric
              label="total"
              value={metrics.totalMessages.toLocaleString()}
            />
            <Metric
              label="errors"
              value={String(metrics.totalErrors)}
              tone={metrics.totalErrors > 0 ? "rose" : undefined}
            />
            <Metric label="queue" value={String(metrics.queueDepth)} />
            <Metric label="nodes" value={String(metrics.nodes)} />
            <Metric label="uptime" value={fmtUptime(metrics.uptimeMs)} />
          </div>
        )}
        {/* biome-ignore lint/a11y/noStaticElementInteractions: React Flow drop target */}
        <div
          className="min-h-0 flex-1 overflow-hidden rounded-xl border border-white/5 bg-zinc-900/40"
          onDrop={onDrop}
          onDragOver={(e) => {
            e.preventDefault();
            e.dataTransfer.dropEffect = "move";
          }}
        >
          <ReactFlow
            nodes={rfNodes}
            edges={rfEdges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            colorMode="dark"
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeDoubleClick={(_, n) => setEditId(n.id)}
            onBeforeDelete={async () => {
              snapshot(); // capture state before a node/edge deletion so it can be undone
              return true;
            }}
            deleteKeyCode={["Backspace", "Delete"]}
            defaultEdgeOptions={{
              type: "default",
              animated: true,
              style: { stroke: "#9ca3af", strokeWidth: 2 },
            }}
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={18} size={1} />
            <MiniMap
              pannable
              zoomable
              className="!bg-zinc-900"
              maskColor="rgba(9, 9, 11, 0.72)"
              nodeColor={(n) => nodeMiniColor(n.type)}
              nodeStrokeColor="#27272a"
              nodeStrokeWidth={2}
              nodeBorderRadius={3}
            />
            <Controls />
          </ReactFlow>
        </div>
      </div>

      {editNode && (
        <FlowEditDialog
          node={editNode}
          brokers={brokers}
          allNodes={rfNodes}
          configs={configs}
          onChange={update}
          onDelete={() => {
            setNodes((nds) => nds.filter((n) => n.id !== editNode.id));
            setEdges((eds) =>
              eds.filter(
                (e) => e.source !== editNode.id && e.target !== editNode.id,
              ),
            );
            setEditId(null);
          }}
          onClose={() => setEditId(null)}
        />
      )}
      {brokersOpen && (
        <BrokerDialog
          brokers={brokers}
          onChange={(b) => {
            setBrokers(b);
            persistBrokers(b);
          }}
          onClose={() => setBrokersOpen(false)}
        />
      )}
      {envOpen && (
        <EnvDialog
          global={globalEnv}
          flowName={flows.find((f) => f.id === activeId)?.name ?? ""}
          flowEnv={flows.find((f) => f.id === activeId)?.env ?? {}}
          onChangeGlobal={(e) => {
            setGlobalEnv(e);
            persistEnv(e);
          }}
          onChangeFlow={(e) => {
            const active = flows.find((f) => f.id === activeId);
            if (!active) return;
            const updated = { ...active, env: e };
            const next = flows.map((f) => (f.id === activeId ? updated : f));
            setFlows(next);
            persistFlows(next);
            queueServerSave(updated);
          }}
          onClose={() => setEnvOpen(false)}
        />
      )}
      {configsOpen && (
        <ConfigsDialog
          configs={configs}
          onChange={(c) => {
            setConfigs(c);
            persistConfigs(c);
          }}
          onClose={() => setConfigsOpen(false)}
        />
      )}
      {projectsOpen && (
        <ProjectsDialog
          onReverted={reloadFlows}
          onClose={() => setProjectsOpen(false)}
        />
      )}
      {newFlowOpen && (
        <NewFlowDialog
          defaultName={`Flow ${flows.length + 1}`}
          onCreate={(name) => {
            createFlow(name);
            setNewFlowOpen(false);
          }}
          onClose={() => setNewFlowOpen(false)}
        />
      )}
    </div>
  );
}

function NewFlowDialog({
  defaultName,
  onCreate,
  onClose,
}: {
  defaultName: string;
  onCreate: (name: string) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(defaultName);
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    ref.current?.focus();
    ref.current?.select(); // pre-select the default name so typing replaces it
  }, []);
  const submit = () => {
    const n = name.trim();
    if (n) onCreate(n);
  };
  return (
    <Modal title="New flow" onClose={onClose} className="w-80 max-w-[90vw]">
      <input
        ref={ref}
        className={`${inputClass} mt-2 w-full`}
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") submit();
        }}
        placeholder="flow name"
      />
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={submit} disabled={!name.trim()}>
          Create
        </Button>
      </div>
    </Modal>
  );
}

// Projects: git version history of the flow library — commit a snapshot, view the log, revert to a commit.
function ProjectsDialog({
  onReverted,
  onClose,
}: {
  onReverted: () => Promise<void> | void;
  onClose: () => void;
}) {
  const admin = isAdmin();
  const [status, setStatus] = useState<ProjectStatus | null>(null);
  const [history, setHistory] = useState<ProjectCommit[]>([]);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmRevert, setConfirmRevert] = useState<string | null>(null);
  // which diff is expanded ("uncommitted" or a commit id) + its loaded content
  const [diffKey, setDiffKey] = useState<string | null>(null);
  const [diff, setDiff] = useState<DiffResult | null>(null);

  async function refresh() {
    try {
      const [s, h] = await Promise.all([
        api.projectStatus(),
        api.projectHistory(50),
      ]);
      setStatus(s);
      setHistory(h);
      setDiffKey(null); // library changed — any open diff is stale
      setDiff(null);
    } catch (e) {
      toast(`Could not load history: ${(e as Error).message}`, "error");
    }
  }

  // toggle the diff panel for a key ("uncommitted" or a commit id); loads it on open
  async function toggleDiff(key: string, commit?: string) {
    if (diffKey === key) {
      setDiffKey(null);
      setDiff(null);
      return;
    }
    setDiffKey(key);
    setDiff(null);
    try {
      setDiff(await api.projectDiff(commit));
    } catch (e) {
      toast(`Diff failed: ${(e as Error).message}`, "error");
      setDiffKey(null);
    }
  }

  // biome-ignore lint/correctness/useExhaustiveDependencies: load once on open
  useEffect(() => {
    refresh();
  }, []);

  async function doCommit() {
    setBusy(true);
    try {
      const r = await api.projectCommit(message.trim());
      toast(
        r.committed ? `Committed "${r.commit?.shortId}"` : r.message,
        r.committed ? "success" : "info",
      );
      setMessage("");
      await refresh();
    } catch (e) {
      toast(`Commit failed: ${(e as Error).message}`, "error");
    } finally {
      setBusy(false);
    }
  }

  async function doRevert(commit: string) {
    setBusy(true);
    setConfirmRevert(null);
    try {
      const r = await api.projectRevert(commit);
      toast(
        `Reverted — ${r.restored} restored, ${r.removed} removed`,
        "success",
      );
      await onReverted();
      await refresh();
    } catch (e) {
      toast(`Revert failed: ${(e as Error).message}`, "error");
    } finally {
      setBusy(false);
    }
  }

  const inp = `${inputClass} w-full`;
  return (
    <Modal
      title={
        <>
          Project history <span className="text-zinc-500">(git)</span>
        </>
      }
      onClose={onClose}
      className="w-[34rem] max-w-[90vw]"
    >
      <p className="mb-3 text-xs leading-relaxed text-zinc-500">
        Version your whole flow library. Commit a snapshot, then revert to any
        earlier commit. Flow graphs are stored with secrets encrypted at rest.
      </p>

      {!admin && (
        <p className="mb-3 rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-300">
          Committing and reverting require an admin account.
        </p>
      )}

      <div className="mb-4 flex items-center gap-2">
        <input
          className={inp}
          placeholder="Commit message (e.g. add webhook flow)"
          value={message}
          disabled={!admin || busy}
          onChange={(e) => setMessage(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && admin && !busy) doCommit();
          }}
        />
        <Button onClick={doCommit} disabled={!admin || busy}>
          Commit
        </Button>
      </div>

      <div className="mb-1 flex items-center gap-2 text-xs text-zinc-500">
        <span
          className={`h-2 w-2 rounded-full ${status?.dirty ? "bg-amber-400" : "bg-emerald-500"}`}
        />
        {status?.dirty
          ? "Uncommitted changes in the library"
          : "Library matches the last commit"}
        {status?.dirty && (
          <button
            type="button"
            className="rounded border border-white/10 px-1.5 py-0.5 text-[11px] text-zinc-400 hover:bg-white/5"
            onClick={() => toggleDiff("uncommitted")}
          >
            {diffKey === "uncommitted" ? "hide changes" : "view changes"}
          </button>
        )}
        <span className="ml-auto">{status?.commits ?? 0} commit(s)</span>
      </div>
      {diffKey === "uncommitted" && (
        <div className="mb-3">
          <DiffView diff={diff} />
        </div>
      )}

      <div className="mt-3 space-y-2">
        {history.length === 0 && (
          <p className="text-xs text-zinc-600">
            No commits yet — commit to create the first snapshot.
          </p>
        )}
        {history.map((c, i) => (
          <div
            key={c.id}
            className="rounded-md border border-white/10 p-2.5 text-sm"
          >
            <div className="flex items-center gap-2">
              <span className="font-mono text-xs text-indigo-300">
                {c.shortId}
              </span>
              {i === 0 && (
                <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-[10px] text-emerald-300">
                  HEAD
                </span>
              )}
              <span className="ml-auto text-[11px] text-zinc-500">
                {new Date(c.at).toLocaleString()}
              </span>
            </div>
            <div className="mt-1 text-zinc-200">{c.message}</div>
            <div className="mt-1 flex items-center gap-2">
              <span className="text-[11px] text-zinc-500">{c.author}</span>
              <button
                type="button"
                className="rounded border border-white/10 px-2 py-0.5 text-[11px] text-zinc-400 hover:bg-white/5"
                onClick={() => toggleDiff(c.id, c.id)}
              >
                {diffKey === c.id ? "hide diff" : "diff"}
              </button>
              {confirmRevert === c.id ? (
                <span className="ml-auto flex items-center gap-1.5">
                  <span className="text-[11px] text-rose-300">
                    Replace library?
                  </span>
                  <button
                    type="button"
                    className="rounded border border-rose-500/40 px-2 py-0.5 text-[11px] text-rose-300 hover:bg-rose-500/10"
                    onClick={() => doRevert(c.id)}
                  >
                    Revert
                  </button>
                  <button
                    type="button"
                    className="rounded border border-white/10 px-2 py-0.5 text-[11px] text-zinc-400 hover:bg-white/5"
                    onClick={() => setConfirmRevert(null)}
                  >
                    Cancel
                  </button>
                </span>
              ) : (
                <button
                  type="button"
                  disabled={!admin || busy || i === 0}
                  className="ml-auto rounded border border-white/10 px-2 py-0.5 text-[11px] text-zinc-400 hover:bg-white/5 disabled:opacity-40"
                  onClick={() => setConfirmRevert(c.id)}
                >
                  {i === 0 ? "current" : "Revert to this"}
                </button>
              )}
            </div>
            {diffKey === c.id && (
              <div className="mt-2">
                <DiffView diff={diff} />
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="mt-4 flex justify-end">
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
      </div>
    </Modal>
  );
}

// Renders a Projects diff (revert preview or uncommitted changes): per-file status + colored patch.
function DiffView({ diff }: { diff: DiffResult | null }) {
  if (diff === null) {
    return <p className="text-[11px] text-zinc-600">loading diff…</p>;
  }
  if (diff.files.length === 0) {
    return (
      <p className="rounded-md border border-white/10 bg-black/20 px-3 py-2 text-[11px] text-zinc-500">
        No differences.
      </p>
    );
  }
  const badge = (s: FileChange["status"]) =>
    s === "ADDED"
      ? "bg-emerald-500/15 text-emerald-300"
      : s === "DELETED"
        ? "bg-rose-500/15 text-rose-300"
        : "bg-amber-500/15 text-amber-300";
  return (
    <div className="space-y-2">
      <div className="text-[10px] uppercase tracking-wider text-zinc-600">
        {diff.from} → {diff.to}
      </div>
      {diff.files.map((f) => (
        <div
          key={f.file}
          className="rounded-md border border-white/10 bg-black/20"
        >
          <div className="flex items-center gap-2 border-b border-white/5 px-2.5 py-1.5">
            <span className="font-mono text-[11px] text-zinc-300">
              {f.file}
            </span>
            <span
              className={`rounded px-1.5 py-0.5 text-[10px] ${badge(f.status)}`}
            >
              {f.status.toLowerCase()}
            </span>
            <span className="ml-auto font-mono text-[10px]">
              <span className="text-emerald-400">+{f.added}</span>{" "}
              <span className="text-rose-400">-{f.removed}</span>
            </span>
          </div>
          {f.patch && (
            <pre className="max-h-56 overflow-auto px-2.5 py-1.5 text-[11px] leading-relaxed">
              {f.patch.split("\n").map((line, i) => {
                const cls = line.startsWith("+")
                  ? "text-emerald-300"
                  : line.startsWith("-")
                    ? "text-rose-300"
                    : line.startsWith("@@")
                      ? "text-sky-400"
                      : "text-zinc-500";
                return (
                  // biome-ignore lint/suspicious/noArrayIndexKey: patch lines are positional
                  <div key={i} className={cls}>
                    {line || " "}
                  </div>
                );
              })}
            </pre>
          )}
        </div>
      ))}
    </div>
  );
}

// edit global + flow-scoped environment variables (used as ${VAR} in configs and env.VAR in expressions)
// manage named shared config nodes (reusable key/value bundles referenced by nodes via configRef)
function ConfigsDialog({
  configs,
  onChange,
  onClose,
}: {
  configs: SharedConfig[];
  onChange: (c: SharedConfig[]) => void;
  onClose: () => void;
}) {
  const inp = `${inputClass} w-full`;
  const setOne = (i: number, patch: Partial<SharedConfig>) =>
    onChange(configs.map((c, j) => (j === i ? { ...c, ...patch } : c)));
  return (
    <Modal
      title="Shared config nodes"
      onClose={onClose}
      className="w-[30rem] max-w-[90vw]"
    >
      <p className="mb-3 text-xs leading-relaxed text-zinc-500">
        Reusable key/value bundles. Reference one from any node's{" "}
        <span className="font-mono">shared config</span> field — its props are
        merged into that node's config at deploy.
      </p>
      <div className="space-y-3">
        {configs.map((cfg, i) => (
          <div key={cfg.id} className="rounded-md border border-white/10 p-2">
            <div className="mb-1.5 flex items-center gap-2">
              <input
                className={inp}
                value={cfg.name}
                onChange={(e) => setOne(i, { name: e.target.value })}
              />
              <button
                type="button"
                aria-label="delete config"
                className="shrink-0 rounded-md border border-white/10 px-2 text-zinc-500 hover:bg-white/5 hover:text-rose-300"
                onClick={() => onChange(configs.filter((_, j) => j !== i))}
              >
                ✕
              </button>
            </div>
            <KeyValueEditor
              label="props"
              value={cfg.props}
              onChange={(p) => setOne(i, { props: p })}
            />
          </div>
        ))}
        {configs.length === 0 && (
          <p className="text-xs text-zinc-600">no shared configs yet</p>
        )}
      </div>
      <div className="mt-4 flex items-center justify-between">
        <button
          type="button"
          className="rounded-md border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5"
          onClick={() =>
            onChange([
              ...configs,
              {
                id: `cfg-${crypto.randomUUID()}`,
                name: `config ${configs.length + 1}`,
                props: {},
              },
            ])
          }
        >
          + config
        </button>
        <Button onClick={onClose}>Done</Button>
      </div>
    </Modal>
  );
}

function EnvDialog({
  global,
  flowName,
  flowEnv,
  onChangeGlobal,
  onChangeFlow,
  onClose,
}: {
  global: Record<string, string>;
  flowName: string;
  flowEnv: Record<string, string>;
  onChangeGlobal: (e: Record<string, string>) => void;
  onChangeFlow: (e: Record<string, string>) => void;
  onClose: () => void;
}) {
  return (
    <Modal
      title="Environment variables"
      onClose={onClose}
      className="w-[28rem] max-w-[90vw]"
    >
      <p className="mb-3 text-xs leading-relaxed text-zinc-500">
        Use as <span className="font-mono">${"{VAR}"}</span> in any config
        field, or <span className="font-mono">env.VAR</span> in expressions.
        Flow vars override global.
      </p>
      <KeyValueEditor label="global" value={global} onChange={onChangeGlobal} />
      <KeyValueEditor
        label={`flow: ${flowName}`}
        value={flowEnv}
        onChange={onChangeFlow}
      />
      <div className="mt-4 flex justify-end">
        <Button onClick={onClose}>Done</Button>
      </div>
    </Modal>
  );
}

function BrokerDialog({
  brokers,
  onChange,
  onClose,
}: {
  brokers: Broker[];
  onChange: (b: Broker[]) => void;
  onClose: () => void;
}) {
  const inp = `${inputClass} w-full`;
  const set = (id: string, patch: Partial<Broker>) =>
    onChange(brokers.map((b) => (b.id === id ? { ...b, ...patch } : b)));
  return (
    <Modal
      title="MQTT brokers"
      onClose={onClose}
      className="w-[30rem] max-w-[90vw]"
    >
      <p className="mb-3 text-xs text-zinc-500">
        Shared connections referenced by MQTT in/out nodes.
      </p>
      <div className="space-y-3">
        {brokers.map((b) => (
          <div key={b.id} className="rounded-lg border border-white/10 p-2.5">
            <div className="mb-2 flex items-center gap-2">
              <input
                className={`${inputClass} flex-1 font-semibold`}
                value={b.name}
                onChange={(e) => set(b.id, { name: e.target.value })}
              />
              <button
                type="button"
                aria-label="delete broker"
                className="px-1 text-zinc-500 hover:text-rose-400"
                onClick={() => onChange(brokers.filter((x) => x.id !== b.id))}
              >
                ✕
              </button>
            </div>
            <input
              className={`${inp} mb-1.5`}
              placeholder="tcp://host:1883"
              value={b.brokerUrl}
              onChange={(e) => set(b.id, { brokerUrl: e.target.value })}
            />
            <div className="flex gap-2">
              <input
                className={inp}
                placeholder="username (optional)"
                value={b.username ?? ""}
                onChange={(e) => set(b.id, { username: e.target.value })}
              />
              <input
                className={inp}
                type="password"
                placeholder="password (optional)"
                value={b.password ?? ""}
                onChange={(e) => set(b.id, { password: e.target.value })}
              />
            </div>
          </div>
        ))}
        {brokers.length === 0 && (
          <p className="text-xs text-zinc-600">no brokers yet</p>
        )}
      </div>
      <div className="mt-4 flex items-center justify-between">
        <button
          type="button"
          className="rounded-md border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5"
          onClick={() =>
            onChange([
              ...brokers,
              {
                id: `b-${crypto.randomUUID()}`,
                name: `broker ${brokers.length + 1}`,
                brokerUrl: "tcp://mosquitto:1883",
              },
            ])
          }
        >
          + broker
        </button>
        <Button onClick={onClose}>Done</Button>
      </div>
    </Modal>
  );
}

function fmtUptime(ms: number): string {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function Metric({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "emerald" | "rose";
}) {
  const color =
    tone === "emerald"
      ? "text-emerald-400"
      : tone === "rose"
        ? "text-rose-400"
        : "text-zinc-200";
  return (
    <span className="flex items-baseline gap-1">
      <span className="text-zinc-500">{label}</span>
      <span className={`font-mono font-semibold tabular-nums ${color}`}>
        {value}
      </span>
    </span>
  );
}

// editor for a string→string map config field (e.g. HTTP headers). Empty keys are dropped on emit.
function KeyValueEditor({
  label,
  value,
  onChange,
}: {
  label: string;
  value?: Record<string, string>;
  onChange: (v: Record<string, string>) => void;
}) {
  const [rows, setRows] = useState<[string, string][]>(() =>
    Object.entries(value ?? {}),
  );
  const push = (next: [string, string][]) => {
    setRows(next);
    onChange(Object.fromEntries(next.filter(([k]) => k.trim())));
  };
  const inp = `${inputClass} w-full`;
  return (
    <L label={label}>
      <div className="space-y-1.5">
        {rows.map(([k, v], i) => (
          // biome-ignore lint/suspicious/noArrayIndexKey: rows are edited in place, never reordered
          <div key={i} className="flex gap-1.5">
            <input
              className={inp}
              placeholder="name"
              value={k}
              onChange={(e) =>
                push(rows.map((r, j) => (j === i ? [e.target.value, r[1]] : r)))
              }
            />
            <input
              className={inp}
              placeholder="value"
              value={v}
              onChange={(e) =>
                push(rows.map((r, j) => (j === i ? [r[0], e.target.value] : r)))
              }
            />
            <button
              type="button"
              aria-label="remove"
              className="shrink-0 rounded-md border border-white/10 px-2 text-zinc-500 hover:bg-white/5 hover:text-rose-300"
              onClick={() => push(rows.filter((_, j) => j !== i))}
            >
              ✕
            </button>
          </div>
        ))}
        <button
          type="button"
          className="rounded-md border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5"
          onClick={() => setRows([...rows, ["", ""]])}
        >
          + row
        </button>
      </div>
    </L>
  );
}

function L({ label, children }: { label: string; children: React.ReactNode }) {
  // a real <label> so the caption is programmatically associated with its control (implicit labelling)
  return (
    // biome-ignore lint/a11y/noLabelWithoutControl: the control is passed in as {children}
    <label className="mb-3 block">
      <span className="mb-1 block text-[11px] uppercase tracking-wider text-zinc-500">
        {label}
      </span>
      {children}
    </label>
  );
}

function FlowEditDialog({
  node,
  brokers,
  allNodes,
  configs,
  onChange,
  onDelete,
  onClose,
}: {
  node: Node;
  brokers: Broker[];
  allNodes: Node[];
  configs: SharedConfig[];
  onChange: (c: Cfg) => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const t = String(node.type);
  const c = (node.data.config ?? {}) as Cfg;
  const set = (k: string, v: unknown) => onChange({ ...c, [k]: v });
  const inp = `${inputClass} w-full`;
  // script-ish nodes (function/template/soap) get a resizable editor + an expand toggle
  const [expanded, setExpanded] = useState(false);
  const codeArea = `${inp} font-mono resize-y ${expanded ? "h-[62vh]" : "h-40"}`;

  const text = (label: string, k: string) => (
    <L label={label}>
      <input
        className={inp}
        value={String(c[k] ?? "")}
        onChange={(e) => set(k, e.target.value)}
      />
    </L>
  );

  return (
    <Modal
      title={
        <span className="flex items-center justify-between gap-4">
          <span className="capitalize">{t} node</span>
          <button
            type="button"
            aria-label={expanded ? "restore size" : "expand editor"}
            onClick={() => setExpanded((v) => !v)}
            className="rounded-md border border-white/10 px-2 py-1 text-xs font-normal text-zinc-400 hover:bg-white/5 hover:text-zinc-100"
          >
            {expanded ? "⤡ restore" : "⤢ expand"}
          </button>
        </span>
      }
      onClose={onClose}
      className={expanded ? "w-[80vw] max-w-5xl" : "w-[26rem] max-w-[90vw]"}
    >
      {t === "inject" && (
        <>
          {text("interval (ms, 0 = once)", "intervalMs")}
          {text("payload ($timestamp for now)", "payload")}
          {text("topic", "topic")}
          <label className="flex items-center gap-2 text-xs text-zinc-300">
            <input
              type="checkbox"
              checked={c.once !== false}
              onChange={(e) => set("once", e.target.checked)}
            />
            auto-fire on deploy (uncheck for manual ▸ only)
          </label>
        </>
      )}
      {t === "change" && (
        <ChangeRules
          rules={changeRulesOf(c)}
          onChange={(r) => set("rules", r)}
        />
      )}
      {t === "switch" && (
        <>
          {text("property", "property")}
          <SwitchRules
            rules={(c.rules as Rule[]) ?? []}
            onChange={(r) => set("rules", r)}
          />
        </>
      )}
      {t === "template" && (
        <>
          {text("property", "property")}
          <L label="template — {{ expr }}, e.g. {{payload * 1.8 + 32}}">
            <textarea
              className={codeArea}
              value={String(c.template ?? "")}
              onChange={(e) => set("template", e.target.value)}
            />
          </L>
        </>
      )}
      {t === "range" && (
        <>
          {text("property", "property")}
          <div className="flex gap-2">
            {text("in min", "inMin")}
            {text("in max", "inMax")}
          </div>
          <div className="flex gap-2">
            {text("out min", "outMin")}
            {text("out max", "outMax")}
          </div>
        </>
      )}
      {t === "delay" && text("delay (ms)", "ms")}
      {(t === "json" || t === "xml" || t === "yaml") && (
        <L label="action">
          <select
            className={inp}
            value={String(c.action ?? "auto")}
            onChange={(e) => set("action", e.target.value)}
          >
            <option value="auto">auto (by payload type)</option>
            <option value="obj">parse → object</option>
            <option value="str">serialize → string</option>
          </select>
        </L>
      )}
      {t === "sort" && (
        <>
          <L label="order">
            <select
              className={inp}
              value={String(c.order ?? "asc")}
              onChange={(e) => set("order", e.target.value)}
            >
              <option value="asc">ascending</option>
              <option value="desc">descending</option>
            </select>
          </L>
          {text("key (for lists of objects, optional)", "prop")}
          <label className="flex items-center gap-2 text-xs text-zinc-300">
            <input
              type="checkbox"
              checked={c.numeric === true}
              onChange={(e) => set("numeric", e.target.checked)}
            />
            numeric compare
          </label>
        </>
      )}
      {t === "batch" && (
        <>
          <L label="mode">
            <select
              className={inp}
              value={String(c.mode ?? "count")}
              onChange={(e) => set("mode", e.target.value)}
            >
              <option value="count">after N messages</option>
              <option value="interval">on a time interval</option>
            </select>
          </L>
          {String(c.mode ?? "count") === "interval"
            ? text("interval (ms)", "intervalMs")
            : text("count", "count")}
        </>
      )}
      {t === "html" && (
        <>
          {text("CSS selector", "selector")}
          <L label="output">
            <select
              className={inp}
              value={String(c.output ?? "text")}
              onChange={(e) => set("output", e.target.value)}
            >
              <option value="text">text of each match</option>
              <option value="html">outer HTML of each match</option>
            </select>
          </L>
        </>
      )}
      {t === "exec" && (
        <>
          {text("command (blank = use msg.payload)", "command")}
          {text("args (space-separated)", "args")}
          {text("timeout (ms)", "timeoutMs")}
          <label className="flex items-center gap-2 text-xs text-zinc-300">
            <input
              type="checkbox"
              checked={c.useShell === true}
              onChange={(e) => set("useShell", e.target.checked)}
            />
            run via shell (sh -c)
          </label>
          <p className="text-[10px] text-zinc-500">
            Outputs: stdout → port 0, stderr → port 1, return code → port 2.
          </p>
        </>
      )}
      {t === "deviceread" && (
        <>
          <div className="flex gap-2">
            <L label="adapter">
              <select
                className={inp}
                value={String(c.adapterType ?? "JDBC")}
                onChange={(e) => set("adapterType", e.target.value)}
              >
                {["JDBC", "MODBUS", "SHELL", "FILE", "API", "SCRIPT_JAVA"].map(
                  (o) => (
                    <option key={o}>{o}</option>
                  ),
                )}
              </select>
            </L>
            <L label="task type">
              <select
                className={inp}
                value={String(c.taskType ?? "QUERY")}
                onChange={(e) => set("taskType", e.target.value)}
              >
                {[
                  "QUERY",
                  "MODBUS_READ",
                  "SHELL",
                  "FILE_READ",
                  "SCRIPT_JAVA",
                ].map((o) => (
                  <option key={o}>{o}</option>
                ))}
              </select>
            </L>
          </div>
          <KeyValueEditor
            label="params (e.g. jdbcUrl, host, port)"
            value={c.params as Record<string, string> | undefined}
            onChange={(p) => set("params", p)}
          />
          <KeyValueEditor
            label="definition (e.g. query, registerType, address)"
            value={c.definition as Record<string, string> | undefined}
            onChange={(d) => set("definition", d)}
          />
          <KeyValueEditor
            label="secrets (e.g. password) — encrypted at rest"
            value={c.secrets as Record<string, string> | undefined}
            onChange={(s) => set("secrets", s)}
          />
          {text("timeout (ms)", "timeoutMs")}
          <p className="text-[10px] leading-relaxed text-zinc-500">
            Runs the adapter on each incoming message → emits the raw result
            (rows / text). Wire an <span className="font-mono">inject</span>{" "}
            timer in to poll, then a <span className="font-mono">tag</span> node
            to store — this is a full Pipeline collection expressed as a flow.
          </p>
        </>
      )}
      {t === "udpin" && text("listen port", "port")}
      {t === "udpout" && (
        <>
          {text("host", "host")}
          {text("port", "port")}
        </>
      )}
      {t === "tag" && (
        <>
          {text("tag (canonicalKey, e.g. line1.temp)", "tag")}
          <p className="text-[10px] leading-relaxed text-zinc-500">
            Writes <span className="font-mono">msg.payload</span> to an existing
            historian tag — the value flows to Time Machine and the gateway,
            exactly like a scheduled collection. Create the tag first on the
            Tags page.
          </p>
        </>
      )}
      {t === "filein" && text("filename (under flow files dir)", "filename")}
      {t === "fileout" && (
        <>
          {text("filename (under flow files dir)", "filename")}
          <L label="action">
            <select
              className={inp}
              value={String(c.action ?? "append")}
              onChange={(e) => set("action", e.target.value)}
            >
              <option value="append">append line</option>
              <option value="write">overwrite</option>
              <option value="delete">delete file</option>
            </select>
          </L>
        </>
      )}
      {t === "rbe" && (
        <p className="text-[11px] leading-relaxed text-zinc-400">
          Report-by-exception: forwards a message only when its payload differs
          from the previous one. Consecutive equal payloads are dropped.
        </p>
      )}
      {t === "trigger" && (
        <>
          {text("delay before 'then' (ms)", "delayMs")}
          {text('then payload (e.g. "reset")', "thenPayload")}
          <p className="text-[10px] text-zinc-500">
            On input: emits the message immediately, then emits the 'then'
            payload after the delay.
          </p>
        </>
      )}
      {t === "csv" && (
        <>
          {text("delimiter", "delimiter")}
          <p className="text-[10px] text-zinc-500">
            Splits a string payload into a trimmed array by this delimiter.
          </p>
        </>
      )}
      {t === "comment" && (
        <L label="note text">
          <textarea
            className={`${inp} h-24`}
            value={String(c.text ?? "")}
            onChange={(e) => set("text", e.target.value)}
          />
        </L>
      )}
      {t === "group" && text("group label", "label")}
      {t === "subflow" && (
        <p className="text-[11px] leading-relaxed text-zinc-400">
          Instance of subflow{" "}
          <span className="font-semibold text-violet-300">
            {String(c.name ?? c.ref ?? "?")}
          </span>
          . Edit the template in its own tab; it's inlined into this flow at
          deploy.
        </p>
      )}
      {(t === "subin" || t === "subout") && (
        <>
          <p className="mb-2 text-[11px] leading-relaxed text-zinc-400">
            Subflow {t === "subin" ? "input" : "output"} marker — wire the
            template's nodes {t === "subin" ? "from here" : "into here"}. The
            port number is the instance's {t === "subin" ? "input" : "output"}{" "}
            index.
          </p>
          {text("port index", "port")}
        </>
      )}
      {t === "function" && (
        <>
          <L label="language">
            <select
              className={inp}
              value={String(c.lang ?? "java")}
              onChange={(e) => set("lang", e.target.value)}
            >
              <option value="js">JavaScript (sandboxed)</option>
              <option value="java">Java</option>
            </select>
          </L>
          <L
            label={
              String(c.lang ?? "java") === "js"
                ? "code — msg, node, flow, global, env in scope"
                : "code — run(Map msg, Map flow, Map global)"
            }
          >
            <FunctionCodeField
              lang={String(c.lang ?? "java") === "js" ? "js" : "java"}
              value={String(c.code ?? "")}
              onChange={(v) => set("code", v)}
              height={expanded ? "62vh" : "16rem"}
            />
          </L>
          <div className="flex gap-2">
            {text("timeout (ms)", "timeoutMs")}
            {text("outputs", "outputs")}
          </div>
          <p className="text-[10px] leading-relaxed text-zinc-500">
            {String(c.lang ?? "java") === "js" ? (
              <>
                Node-RED-style: mutate <span className="font-mono">msg</span>{" "}
                and <span className="font-mono">return msg</span>, or use{" "}
                <span className="font-mono">node.send([m1,m2])</span> for
                multiple outputs,{" "}
                <span className="font-mono">flow.get/set</span>,{" "}
                <span className="font-mono">node.status/error/warn</span>. Runs
                in a sandbox (no JVM/file/network access).
              </>
            ) : (
              <>
                For multiple outputs, return an{" "}
                <span className="font-mono">Object[]</span> — element i goes to
                port i (null skips a port).
              </>
            )}
          </p>
        </>
      )}
      {t === "httprequest" && (
        <>
          <L label="method">
            <select
              className={inp}
              value={String(c.method ?? "GET")}
              onChange={(e) => set("method", e.target.value)}
            >
              {["GET", "POST", "PUT", "DELETE", "PATCH"].map((o) => (
                <option key={o}>{o}</option>
              ))}
            </select>
          </L>
          {text("URL", "url")}
          <KeyValueEditor
            label="headers"
            value={c.headers as Record<string, string> | undefined}
            onChange={(h) => set("headers", h)}
          />
          <p className="text-[10px] text-zinc-500">
            msg.payload is sent as the request body (POST/PUT/PATCH). Response
            body → msg.payload, status → msg.statusCode. msg.url overrides this
            URL.
          </p>
        </>
      )}
      {t === "soaprequest" && (
        <>
          {text("endpoint URL", "url")}
          {text("SOAPAction", "soapAction")}
          <L label="envelope (use {{prop}})">
            <textarea
              className={codeArea}
              value={String(c.envelope ?? "")}
              onChange={(e) => set("envelope", e.target.value)}
            />
          </L>
        </>
      )}
      {t === "split" && text("delimiter (for text payloads)", "delimiter")}
      {t === "join" && text("count (messages → array)", "count")}
      {t === "httpin" && (
        <>
          <L label="method">
            <select
              className={inp}
              value={String(c.method ?? "POST")}
              onChange={(e) => set("method", e.target.value)}
            >
              {["GET", "POST", "PUT"].map((o) => (
                <option key={o}>{o}</option>
              ))}
            </select>
          </L>
          {text("path (called as /api/flows/in/<path>)", "path")}
          <p className="text-[10px] text-zinc-500">
            public webhook ingress — wire to an HTTP response node to reply.
          </p>
        </>
      )}
      {t === "httpresponse" && (
        <>
          <p className="mb-2 text-xs leading-relaxed text-zinc-400">
            Replies to the caller of the matching{" "}
            <span className="text-teal-300">HTTP in</span> node with{" "}
            <span className="text-sky-300">msg.payload</span>. msg.statusCode /
            msg.headers override the defaults below.
          </p>
          {text("status code", "statusCode")}
          <KeyValueEditor
            label="response headers"
            value={c.headers as Record<string, string> | undefined}
            onChange={(h) => set("headers", h)}
          />
        </>
      )}
      {(t === "catch" || t === "complete" || t === "status") && (
        <L
          label={
            t === "catch"
              ? "catch errors from"
              : t === "status"
                ? "report status of"
                : "fire when these finish"
          }
        >
          <p className="mb-1 text-[10px] text-zinc-500">
            none selected = {t === "catch" ? "all nodes" : "any node"} in this
            flow
          </p>
          <div className="max-h-48 space-y-1 overflow-auto rounded-md border border-white/10 p-2">
            {allNodes
              .filter(
                (n) =>
                  n.id !== node.id &&
                  n.type !== "comment" &&
                  n.type !== "catch" &&
                  n.type !== "complete" &&
                  n.type !== "status",
              )
              .map((n) => {
                const scope = (c.scope as string[]) ?? [];
                const checked = scope.includes(n.id);
                return (
                  <label
                    key={n.id}
                    className="flex items-center gap-2 text-xs text-zinc-300"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={(e) =>
                        set(
                          "scope",
                          e.target.checked
                            ? [...scope, n.id]
                            : scope.filter((x) => x !== n.id),
                        )
                      }
                    />
                    <span className="font-mono text-zinc-400">{n.type}</span>
                    <span className="truncate text-zinc-500">{n.id}</span>
                  </label>
                );
              })}
            {allNodes.filter(
              (n) =>
                n.id !== node.id &&
                n.type !== "comment" &&
                n.type !== "catch" &&
                n.type !== "complete",
            ).length === 0 && (
              <p className="text-zinc-600">no other nodes yet</p>
            )}
          </div>
        </L>
      )}
      {t === "linkin" && (
        <p className="text-[11px] leading-relaxed text-zinc-400">
          Receives messages from{" "}
          <span className="text-stone-300">Link out</span> nodes that target it
          (a virtual wire — no visible connection). Wire its output to continue
          the flow.
        </p>
      )}
      {t === "linkout" && (
        <label className="mb-2 flex items-center gap-2 text-xs text-zinc-300">
          <input
            type="checkbox"
            checked={c.mode === "return"}
            onChange={(e) => set("mode", e.target.checked ? "return" : "link")}
          />
          return mode (send back to the Link call that invoked this)
        </label>
      )}
      {((t === "linkout" && c.mode !== "return") || t === "linkcall") && (
        <L
          label={
            t === "linkcall" ? "call link-in nodes" : "send to link-in nodes"
          }
        >
          <p className="mb-1 text-[10px] text-zinc-500">
            {t === "linkcall"
              ? "invokes the checked Link in subroutine; a return-mode Link out replies"
              : "forwards each message to the checked Link in nodes"}
          </p>
          <div className="max-h-48 space-y-1 overflow-auto rounded-md border border-white/10 p-2">
            {allNodes
              .filter((n) => n.type === "linkin")
              .map((n) => {
                const linkList = (c.links as string[]) ?? [];
                const checked = linkList.includes(n.id);
                return (
                  <label
                    key={n.id}
                    className="flex items-center gap-2 text-xs text-zinc-300"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={(e) =>
                        set(
                          "links",
                          e.target.checked
                            ? [...linkList, n.id]
                            : linkList.filter((x) => x !== n.id),
                        )
                      }
                    />
                    <span className="truncate text-zinc-300">{n.id}</span>
                  </label>
                );
              })}
            {allNodes.filter((n) => n.type === "linkin").length === 0 && (
              <p className="text-zinc-600">
                add a Link in node somewhere first
              </p>
            )}
          </div>
        </L>
      )}
      {(t === "mqttin" || t === "mqttout") && (
        <>
          <L label="broker">
            <select
              className={inp}
              value={String(c.broker ?? "")}
              onChange={(e) => set("broker", e.target.value)}
            >
              <option value="">— inline —</option>
              {brokers.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name} ({b.brokerUrl})
                </option>
              ))}
            </select>
          </L>
          {!c.broker && (
            <>
              {text("broker URL (tcp://host:1883)", "brokerUrl")}
              <div className="flex gap-2">
                {text("username (optional)", "username")}
                {text("password (optional)", "password")}
              </div>
            </>
          )}
          {text(
            t === "mqttin" ? "topic (sub, # = wildcard)" : "topic (pub)",
            "topic",
          )}
          <div className="flex gap-2">
            {text("QoS", "qos")}
            {text("client id (optional)", "clientId")}
          </div>
          {t === "mqttout" && (
            <label className="flex items-center gap-2 text-xs text-zinc-300">
              <input
                type="checkbox"
                checked={Boolean(c.retain)}
                onChange={(e) => set("retain", e.target.checked)}
              />
              retain
            </label>
          )}
        </>
      )}
      {(t === "tcpin" || t === "tcpout") && (
        <div className="flex gap-2">
          {text("host", "host")}
          {text("port", "port")}
        </div>
      )}
      {(t === "wsin" || t === "wsout") &&
        text("URL (ws://host:port/path)", "url")}
      {t === "debug" && (
        <>
          {text("name", "name")}
          {text("property (or $msg)", "property")}
        </>
      )}
      {/* generic shared config reference — its props merge into this node's config at deploy */}
      {!["comment", "group", "subin", "subout"].includes(t) &&
        configs.length > 0 && (
          <L label="shared config">
            <select
              className={inp}
              value={String(c.configRef ?? "")}
              onChange={(e) => set("configRef", e.target.value)}
            >
              <option value="">— none —</option>
              {configs.map((cfg) => (
                <option key={cfg.id} value={cfg.id}>
                  {cfg.name}
                </option>
              ))}
            </select>
          </L>
        )}

      <label className="mt-4 flex items-center gap-2 border-t border-white/10 pt-3 text-xs text-zinc-300">
        <input
          type="checkbox"
          checked={c.disabled !== true}
          onChange={(e) => set("disabled", !e.target.checked)}
        />
        enabled (disabled nodes are skipped on deploy)
      </label>
      <div className="mt-3 flex items-center justify-between">
        <Button variant="danger" onClick={onDelete}>
          Delete
        </Button>
        <Button onClick={onClose}>Done</Button>
      </div>
    </Modal>
  );
}

interface Rule {
  op: string;
  value?: unknown;
}
// switch operators that take no comparison value
const SWITCH_NO_VALUE = [
  "else",
  "true",
  "false",
  "null",
  "nnull",
  "empty",
  "nempty",
];
function SwitchRules({
  rules,
  onChange,
}: {
  rules: Rule[];
  onChange: (r: Rule[]) => void;
}) {
  const inp = `${inputClass} w-full`;
  const set = (i: number, patch: Partial<Rule>) =>
    onChange(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <L label={`rules → outputs (${rules.length})`}>
      <div className="space-y-1.5">
        {rules.map((r, i) => (
          // biome-ignore lint/suspicious/noArrayIndexKey: rule rows map 1:1 to output ports by index
          <div key={i} className="flex items-center gap-1.5">
            <span className="w-4 text-right text-[10px] text-zinc-500">
              {i}
            </span>
            <select
              className={`${inputClass} w-28`}
              value={r.op}
              onChange={(e) => set(i, { op: e.target.value })}
            >
              {[
                "gt",
                "gte",
                "lt",
                "lte",
                "eq",
                "neq",
                "contains",
                "regex",
                "expr",
                "true",
                "false",
                "null",
                "nnull",
                "empty",
                "nempty",
                "else",
              ].map((o) => (
                <option key={o}>{o}</option>
              ))}
            </select>
            {!SWITCH_NO_VALUE.includes(r.op) && (
              <input
                className={inp}
                value={String(r.value ?? "")}
                onChange={(e) => set(i, { value: e.target.value })}
              />
            )}
            <button
              type="button"
              className="px-1 text-zinc-500 hover:text-rose-300"
              onClick={() => onChange(rules.filter((_, j) => j !== i))}
            >
              ✕
            </button>
          </div>
        ))}
      </div>
      <button
        type="button"
        className="mt-2 rounded-md border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5"
        onClick={() => onChange([...rules, { op: "gt", value: 0 }])}
      >
        + rule
      </button>
    </L>
  );
}

interface ChangeRule {
  t: string; // set | change | delete | move
  p: string;
  to?: unknown;
  tot?: string; // value type for "set"
  from?: string; // search for "change"
  re?: boolean; // regex for "change"
}

// read the change node's rules, migrating a legacy {property,value,valueType} into one "set" rule
function changeRulesOf(c: Cfg): ChangeRule[] {
  const rules = c.rules as ChangeRule[] | undefined;
  if (Array.isArray(rules)) return rules;
  if (c.property)
    return [
      {
        t: "set",
        p: String(c.property),
        to: c.value,
        tot: String(c.valueType ?? "str"),
      },
    ];
  return [];
}

function ChangeRules({
  rules,
  onChange,
}: {
  rules: ChangeRule[];
  onChange: (r: ChangeRule[]) => void;
}) {
  const inp = `${inputClass} w-full`;
  const set = (i: number, patch: Partial<ChangeRule>) =>
    onChange(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <L label={`rules (${rules.length})`}>
      <div className="space-y-2">
        {rules.map((r, i) => (
          <div
            // biome-ignore lint/suspicious/noArrayIndexKey: rules are edited in place
            key={i}
            className="space-y-1.5 rounded-md border border-white/10 p-2"
          >
            <div className="flex gap-1.5">
              <select
                className={`${inputClass} w-24`}
                value={r.t}
                onChange={(e) => set(i, { t: e.target.value })}
              >
                {["set", "change", "delete", "move"].map((o) => (
                  <option key={o}>{o}</option>
                ))}
              </select>
              <input
                className={inp}
                placeholder="property"
                value={r.p ?? ""}
                onChange={(e) => set(i, { p: e.target.value })}
              />
              <button
                type="button"
                className="px-1 text-zinc-500 hover:text-rose-300"
                onClick={() => onChange(rules.filter((_, j) => j !== i))}
              >
                ✕
              </button>
            </div>
            {r.t === "set" && (
              <div className="flex gap-1.5">
                <input
                  className={inp}
                  placeholder={r.tot === "expr" ? "expression" : "to value"}
                  value={String(r.to ?? "")}
                  onChange={(e) => set(i, { to: e.target.value })}
                />
                <select
                  className={`${inputClass} w-20`}
                  value={r.tot ?? "str"}
                  onChange={(e) => set(i, { tot: e.target.value })}
                >
                  {["str", "num", "bool", "msg", "expr"].map((o) => (
                    <option key={o}>{o}</option>
                  ))}
                </select>
              </div>
            )}
            {r.t === "move" && (
              <input
                className={inp}
                placeholder="to property (rename)"
                value={String(r.to ?? "")}
                onChange={(e) => set(i, { to: e.target.value })}
              />
            )}
            {r.t === "change" && (
              <>
                <div className="flex gap-1.5">
                  <input
                    className={inp}
                    placeholder="search"
                    value={r.from ?? ""}
                    onChange={(e) => set(i, { from: e.target.value })}
                  />
                  <input
                    className={inp}
                    placeholder="replace with"
                    value={String(r.to ?? "")}
                    onChange={(e) => set(i, { to: e.target.value })}
                  />
                </div>
                <label className="flex items-center gap-2 text-xs text-zinc-300">
                  <input
                    type="checkbox"
                    checked={!!r.re}
                    onChange={(e) => set(i, { re: e.target.checked })}
                  />
                  regex
                </label>
              </>
            )}
          </div>
        ))}
      </div>
      <button
        type="button"
        className="mt-2 rounded-md border border-white/10 px-2 py-1 text-xs text-zinc-300 hover:bg-white/5"
        onClick={() =>
          onChange([...rules, { t: "set", p: "payload", to: "", tot: "str" }])
        }
      >
        + rule
      </button>
    </L>
  );
}

export function Flows() {
  return (
    <ReactFlowProvider>
      <FlowEditor />
    </ReactFlowProvider>
  );
}
