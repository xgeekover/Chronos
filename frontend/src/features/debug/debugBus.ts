// Tiny module-level pub/sub that wires the Flows canvas to the right Debug sidebar (Node-RED
// style): canvas Debug nodes stream messages here, and the Info tab reflects the selected node.
// Kept outside React so the panel (rendered globally in App) and the flow editor stay decoupled.

export interface DebugMessage {
  id: string;
  at: string; // toLocaleTimeString
  node: string; // debug node label
  topic: string; // tapped tag canonical key
  value: unknown;
  quality?: string;
}

type DebugListener = (msgs: DebugMessage[]) => void;
let debugBuffer: DebugMessage[] = [];
const debugListeners = new Set<DebugListener>();

/** A canvas Debug node emits one message per new sample flowing through it. */
export function publishDebug(m: Omit<DebugMessage, "id" | "at">): void {
  const msg: DebugMessage = {
    ...m,
    id: crypto.randomUUID(),
    at: new Date().toLocaleTimeString(),
  };
  debugBuffer = [msg, ...debugBuffer].slice(0, 200); // newest first, capped ring buffer
  for (const l of debugListeners) l(debugBuffer);
}

export function clearDebug(): void {
  debugBuffer = [];
  for (const l of debugListeners) l(debugBuffer);
}

export function subscribeDebug(l: DebugListener): () => void {
  debugListeners.add(l);
  l(debugBuffer);
  return () => {
    debugListeners.delete(l);
  };
}

// ───────── selected-node info (Info tab) ─────────
export interface NodeInfo {
  type: string;
  title: string;
  fields: [string, string][];
  help?: string; // one-line description of what this node type does
}

type InfoListener = (info: NodeInfo | null) => void;
let currentInfo: NodeInfo | null = null;
const infoListeners = new Set<InfoListener>();

export function publishSelection(info: NodeInfo | null): void {
  currentInfo = info;
  for (const l of infoListeners) l(info);
}

export function subscribeSelection(l: InfoListener): () => void {
  infoListeners.add(l);
  l(currentInfo);
  return () => {
    infoListeners.delete(l);
  };
}

// which flow the Flows editor is currently viewing — lets the Inspector scope its Context query
type FlowListener = (flowId: string | null) => void;
let currentFlow: string | null = null;
const flowListeners = new Set<FlowListener>();

export function publishActiveFlow(flowId: string | null): void {
  currentFlow = flowId;
  for (const l of flowListeners) l(flowId);
}

export function subscribeActiveFlow(l: FlowListener): () => void {
  flowListeners.add(l);
  l(currentFlow);
  return () => {
    flowListeners.delete(l);
  };
}
