import { useState } from "react";

// Collapsible JSON tree for the Debug/Context panels: objects & arrays fold/unfold, primitives are
// shown inline with type colouring. Root is expanded, nested levels start collapsed (narrow panel).

const TONE: Record<string, string> = {
  string: "text-emerald-300",
  number: "text-amber-300",
  boolean: "text-purple-300",
  bigint: "text-amber-300",
};

function primitiveText(v: unknown): string {
  if (v === null) return "null";
  if (v === undefined) return "undefined";
  if (typeof v === "string") return `"${v}"`;
  return String(v);
}

function primitiveTone(v: unknown): string {
  if (v === null || v === undefined) return "text-zinc-500 italic";
  return TONE[typeof v] ?? "text-zinc-200";
}

/** Flat searchable text for a value (so the Debug filter can match inside objects). */
export function jsonText(v: unknown): string {
  if (v !== null && typeof v === "object") {
    try {
      return JSON.stringify(v);
    } catch {
      return String(v);
    }
  }
  return String(v);
}

function previewVal(v: unknown): string {
  if (v !== null && typeof v === "object") {
    return Array.isArray(v) ? "[…]" : "{…}";
  }
  const s = primitiveText(v);
  return s.length > 18 ? `${s.slice(0, 17)}…` : s;
}

/** One-line summary of a collapsed object/array — first few entries, truncated. */
function preview(v: object): string {
  try {
    if (Array.isArray(v)) {
      if (v.length === 0) return "[]";
      const body = v.slice(0, 3).map(previewVal).join(", ");
      return `[ ${body}${v.length > 3 ? ", …" : ""} ]`;
    }
    const entries = Object.entries(v);
    if (entries.length === 0) return "{}";
    const body = entries
      .slice(0, 3)
      .map(([k, val]) => `${k}: ${previewVal(val)}`)
      .join(", ");
    return `{ ${body}${entries.length > 3 ? ", …" : ""} }`;
  } catch {
    return "…";
  }
}

/**
 * If a value is a string that encodes a JSON object/array (common for HTTP/MQTT/device payloads),
 * return the parsed value so it can be explored as a tree; otherwise null (render as a plain string).
 */
function asJsonContainer(v: unknown): object | null {
  if (typeof v !== "string") return null;
  const t = v.trim();
  if (t.length < 2 || !(t[0] === "{" || t[0] === "[")) return null;
  try {
    const parsed = JSON.parse(t);
    return parsed !== null && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

export function JsonView({
  value,
  name,
  depth = 0,
}: {
  value: unknown;
  name?: string;
  depth?: number;
}) {
  // a JSON-encoded string is explored as its parsed tree (flagged so the raw type stays clear)
  const parsed = asJsonContainer(value);
  const node = parsed ?? value;
  const isObject = node !== null && typeof node === "object";
  const [open, setOpen] = useState(depth < 1); // root expanded, nested collapsed

  if (!isObject) {
    return (
      <div className="whitespace-pre-wrap break-all leading-relaxed">
        {name !== undefined && <span className="text-sky-300">{name}: </span>}
        <span className={primitiveTone(value)}>{primitiveText(value)}</span>
      </div>
    );
  }

  const entries: [string, unknown][] = Array.isArray(node)
    ? node.map((v, i) => [String(i), v])
    : Object.entries(node as Record<string, unknown>);
  const label = Array.isArray(node)
    ? `Array(${entries.length})`
    : `{${entries.length}}`;

  return (
    <div className="leading-relaxed">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-start gap-1 rounded text-left hover:bg-white/5"
      >
        <span className="mt-px w-3 shrink-0 text-zinc-500">
          {entries.length === 0 ? "" : open ? "▾" : "▸"}
        </span>
        {name !== undefined && <span className="text-sky-300">{name}:</span>}
        {parsed !== null && (
          <span
            className="shrink-0 rounded bg-white/5 px-1 text-[9px] uppercase text-zinc-500"
            title="value is a JSON-encoded string"
          >
            json
          </span>
        )}
        <span className="shrink-0 text-zinc-500">{label}</span>
        {!open && entries.length > 0 && (
          <span className="min-w-0 flex-1 truncate text-zinc-600">
            {preview(node as object)}
          </span>
        )}
      </button>
      {open && entries.length > 0 && (
        <div className="ml-1.5 border-l border-white/10 pl-2">
          {entries.map(([k, v]) => (
            <JsonView key={k} name={k} value={v} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  );
}
