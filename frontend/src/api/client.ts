// Minimal REST client for the Chronos backend (proxied via Vite to :8080).

export interface Device {
  id: string;
  name: string;
  type: string;
  adapterType: string;
  status: string;
  connectionConfigMeta?: Record<string, unknown>; // non-sensitive params (jdbcUrl, mode, …)
}

// Live connection-pool status (one per pooled DB), for the Devices management view.
export interface Pool {
  adapter: string;
  url: string;
  user: string;
  max: number;
  active: number;
  idle: number;
  total: number;
  awaiting: number;
}

export interface Node {
  id: string;
  name: string;
  description?: string;
  retentionHours: number;
  enabled: boolean;
}

export interface Task {
  id: string;
  nodeId: string;
  deviceId: string;
  type: string;
  scheduleKind: string;
  intervalMs?: number;
  cronExpr?: string;
  timeoutMs?: number;
  definition?: Record<string, unknown>;
  enabled: boolean;
}

export interface TagValue {
  tagKey: string;
  value: unknown;
  quality: string;
  ts: string;
}

export interface Tag {
  id: string;
  nodeId: string;
  name: string;
  canonicalKey: string;
  dataType: string;
  unit?: string;
  description?: string;
}

export interface Snapshot {
  nodeId: string;
  at: string;
  values: Record<string, TagValue>;
}

export interface TagSample {
  tag: string;
  value: unknown;
  quality: string;
  tsMillis: number;
}

export interface DashboardSummary {
  devices: number;
  nodes: number;
  tags: number;
  tasks: number;
  liveValues: number;
  collections: { ok: number; error: number; total: number };
  successRate: number;
}

export interface CollectionLog {
  id: number;
  taskId: string;
  startedAt: string;
  durationMs?: number;
  status: string;
  error?: string;
  tagCount?: number;
}

export interface Mapping {
  id: string;
  taskId: string;
  tagId: string;
  extractor: Record<string, unknown>;
  transform?: Record<string, unknown>;
}

// JWT auth (§11): a bearer token from POST /api/auth/login, kept in localStorage. RBAC roles:
// ADMIN / OPERATOR / VIEWER — the backend enforces; the UI uses the role to gate write controls.
export interface Auth {
  token: string;
  username: string;
  role: string;
}
const AUTH_KEY = "chronos-auth";
export function getAuth(): Auth | null {
  try {
    const raw = localStorage.getItem(AUTH_KEY);
    return raw ? (JSON.parse(raw) as Auth) : null;
  } catch {
    return null;
  }
}
function setAuth(a: Auth) {
  localStorage.setItem(AUTH_KEY, JSON.stringify(a));
}
export function clearAuth() {
  localStorage.removeItem(AUTH_KEY);
}
export function isAdmin() {
  return getAuth()?.role === "ADMIN";
}
export function canOperate() {
  const r = getAuth()?.role;
  return r === "ADMIN" || r === "OPERATOR";
}

// Error that preserves the HTTP status + parsed body so callers can branch (e.g. 409 conflict).
export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, body: unknown, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getAuth()?.token;
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (res.status === 401 && path !== "/auth/login") {
    // token missing/expired — drop it and signal the app to show login (no full reload, so a
    // transient/flapping 401 from background polling doesn't wipe in-progress UI state)
    clearAuth();
    window.dispatchEvent(new Event("chronos-unauthorized"));
    throw new Error("session expired");
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(
      res.status,
      body,
      (body as { error?: string }).error ?? `HTTP ${res.status}`,
    );
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export async function login(username: string, password: string): Promise<Auth> {
  const a = await http<Auth>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
  setAuth(a);
  return a;
}

export const api = {
  listDevices: () => http<Device[]>("/devices"),
  createDevice: (body: unknown) =>
    http<Device>("/devices", { method: "POST", body: JSON.stringify(body) }),
  updateDevice: (id: string, body: unknown) =>
    http<Device>(`/devices/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  listNodes: () => http<Node[]>("/nodes"),
  createNode: (body: unknown) =>
    http<Node>("/nodes", { method: "POST", body: JSON.stringify(body) }),
  listTasks: () => http<Task[]>("/tasks"),
  createTask: (body: unknown) =>
    http<Task>("/tasks", { method: "POST", body: JSON.stringify(body) }),
  updateTask: (id: string, body: unknown) =>
    http<Task>(`/tasks/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  deleteTask: (id: string) => http<void>(`/tasks/${id}`, { method: "DELETE" }),
  deleteDevice: (id: string) =>
    http<void>(`/devices/${id}`, { method: "DELETE" }),
  validateDevice: (id: string) =>
    http<{ status: string }>(`/devices/${id}/validate`, { method: "POST" }),
  devicePools: () => http<Pool[]>("/devices/pools"),
  createMapping: (taskId: string, body: unknown) =>
    http<Mapping>(`/tasks/${taskId}/mappings`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateMapping: (id: string, body: unknown) =>
    http<Mapping>(`/mappings/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteMapping: (id: string) =>
    http<void>(`/mappings/${id}`, { method: "DELETE" }),
  runTask: (id: string) =>
    http<unknown>(`/tasks/${id}/run`, { method: "POST" }),
  currentValues: () => http<TagValue[]>("/values"),
  listTags: (nodeId: string) => http<Tag[]>(`/nodes/${nodeId}/tags`),
  listMappings: (taskId: string) =>
    http<Mapping[]>(`/tasks/${taskId}/mappings`),
  createTag: (nodeId: string, body: unknown) =>
    http<Tag>(`/nodes/${nodeId}/tags`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  updateTag: (id: string, body: unknown) =>
    http<Tag>(`/tags/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  deleteTag: (id: string) => http<void>(`/tags/${id}`, { method: "DELETE" }),
  snapshot: (nodeId: string, atIso: string) =>
    http<Snapshot>(`/nodes/${nodeId}/snapshot?at=${encodeURIComponent(atIso)}`),
  range: (nodeId: string, tag: string, fromIso: string, toIso: string) =>
    http<TagSample[]>(
      `/nodes/${nodeId}/range?tag=${encodeURIComponent(tag)}&from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}`,
    ),
  dashboard: () => http<DashboardSummary>("/dashboard/summary"),
  logs: () => http<CollectionLog[]>("/logs?limit=100"),
  validateScript: (script: string) =>
    http<ScriptValidation>("/scripts/validate", {
      method: "POST",
      body: JSON.stringify({ script }),
    }),
  // Node-RED-style flow runtime (core-flow) — many flows run at once, keyed by flowId
  runFlow: (graph: unknown, flowId: string, name?: string) => {
    const params = new URLSearchParams({ flowId });
    if (name) params.set("name", name);
    return http<{ running: boolean; nodes: number; flowId: string }>(
      `/flows/run?${params.toString()}`,
      { method: "POST", body: JSON.stringify(graph) },
    );
  },
  stopFlow: (flowId?: string) =>
    http<{ running: boolean; stopped: number }>(
      `/flows/stop${flowId ? `?flowId=${encodeURIComponent(flowId)}` : ""}`,
      { method: "POST" },
    ),
  flowStatus: (flowId: string) =>
    http<{
      running: boolean;
      nodes: number;
      counts?: Record<string, number>;
      statuses?: Record<string, string>;
      deployName?: string;
      deployBy?: string;
    }>(`/flows/status?flowId=${encodeURIComponent(flowId)}`),
  // overview of every currently-running flow (for tab indicators)
  listRunningFlows: () =>
    http<{ flows: (FlowMetrics & { flowId: string })[] }>("/flows/status"),
  flowAudit: () => http<FlowAudit[]>("/flows/audit"),
  flowDebug: (flowId: string, since: number) =>
    http<FlowDebugRecord[]>(
      `/flows/debug?flowId=${encodeURIComponent(flowId)}&since=${since}`,
    ),
  injectNow: (nodeId: string, flowId?: string) =>
    http<{ fired: boolean }>(
      `/flows/inject/${nodeId}${flowId ? `?flowId=${encodeURIComponent(flowId)}` : ""}`,
      { method: "POST" },
    ),
  flowContext: (flowId: string) =>
    http<{ flow: Record<string, unknown>; global: Record<string, unknown> }>(
      `/flows/context?flowId=${encodeURIComponent(flowId)}`,
    ),
  flowMetrics: (flowId: string) =>
    http<FlowMetrics>(`/flows/metrics?flowId=${encodeURIComponent(flowId)}`),
  // per-user server-side flow library (multi-user flows; writes are ADMIN-gated)
  listFlowDefs: () => http<FlowDef[]>("/flow-defs"),
  saveFlowDef: (id: string, body: { name: string; graph: FlowGraphDoc }) =>
    http<FlowDef>(`/flow-defs/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteFlowDef: (id: string) =>
    http<void>(`/flow-defs/${id}`, { method: "DELETE" }),
  // Projects: git versioning of the flow library (Node-RED Projects)
  projectStatus: () => http<ProjectStatus>("/projects/status"),
  projectHistory: (limit = 50) =>
    http<ProjectCommit[]>(`/projects/history?limit=${limit}`),
  projectCommit: (message: string) =>
    http<CommitResult>("/projects/commit", {
      method: "POST",
      body: JSON.stringify({ message }),
    }),
  projectRevert: (commit: string) =>
    http<RevertResult>("/projects/revert", {
      method: "POST",
      body: JSON.stringify({ commit }),
    }),
  // diff: no commit → uncommitted changes (HEAD → current); with commit → revert preview (current → commit)
  projectDiff: (commit?: string) =>
    http<DiffResult>(
      `/projects/diff${commit ? `?commit=${encodeURIComponent(commit)}` : ""}`,
    ),
};

export interface FileChange {
  file: string;
  status: "ADDED" | "MODIFIED" | "DELETED";
  added: number;
  removed: number;
  patch: string;
}

export interface DiffResult {
  from: string;
  to: string;
  files: FileChange[];
}

export interface ProjectCommit {
  id: string;
  shortId: string;
  message: string;
  author: string;
  at: string;
}

export interface ProjectStatus {
  initialized: boolean;
  dirty: boolean;
  commits: number;
  head: ProjectCommit | null;
}

export interface CommitResult {
  committed: boolean;
  message: string;
  commit: ProjectCommit | null;
}

export interface RevertResult {
  commit: string;
  restored: number;
  removed: number;
}

export interface FlowGraphDoc {
  nodes: unknown[];
  edges: unknown[];
  subflow?: boolean; // marks a subflow template (inlined at deploy)
  disabled?: boolean; // marks a disabled flow (skipped by deploy all/modified)
  env?: Record<string, string>; // flow-scoped environment variables
}

export interface FlowDef {
  id: string;
  name: string;
  graph: FlowGraphDoc;
  updatedAt: string;
}

export interface FlowMetrics {
  running: boolean;
  uptimeMs: number;
  nodes: number;
  totalMessages: number;
  totalErrors: number;
  queueDepth: number;
  deployName?: string;
  deployBy?: string;
}

export interface FlowAudit {
  id: number;
  at: string;
  actor: string;
  action: "DEPLOY" | "STOP" | "ERROR";
  flowName?: string;
  nodeCount?: number;
  detail?: string;
}

export interface FlowDebugRecord {
  seq: number;
  at: number;
  nodeId: string;
  name: string;
  topic?: unknown;
  payload?: unknown;
}

export interface ScriptValidation {
  valid: boolean;
  error?: string;
  line?: number;
}
