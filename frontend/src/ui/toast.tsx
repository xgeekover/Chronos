import { useEffect, useState } from "react";

// Tiny global toast bus (same publish/subscribe shape as features/debug/debugBus).
// Any module can call toast("saved", "success"); a single <Toaster/> renders the stack.

export type ToastKind = "info" | "success" | "error";
export interface Toast {
  id: number;
  message: string;
  kind: ToastKind;
}

let seq = 0;
let toasts: Toast[] = [];
const subs = new Set<(t: Toast[]) => void>();
const emit = () => {
  for (const s of subs) s(toasts);
};

function dismiss(id: number) {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}

export function toast(message: string, kind: ToastKind = "info") {
  const t: Toast = { id: ++seq, message, kind };
  toasts = [...toasts, t];
  emit();
  setTimeout(() => dismiss(t.id), 4000);
}

const TONE: Record<ToastKind, string> = {
  info: "border-sky-500/30 bg-sky-950/95 text-sky-100",
  success: "border-emerald-500/30 bg-emerald-950/95 text-emerald-100",
  error: "border-rose-500/30 bg-rose-950/95 text-rose-100",
};
const GLYPH: Record<ToastKind, string> = {
  info: "ℹ",
  success: "✓",
  error: "⚠",
};

export function Toaster() {
  const [items, setItems] = useState<Toast[]>(toasts);
  useEffect(() => {
    subs.add(setItems);
    return () => {
      subs.delete(setItems);
    };
  }, []);
  if (items.length === 0) return null;
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-80 max-w-[90vw] flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          className={`pointer-events-auto flex items-start gap-2 rounded-lg border px-3 py-2 text-sm shadow-xl shadow-black/40 ${TONE[t.kind]}`}
        >
          <span className="mt-0.5 shrink-0 font-semibold">{GLYPH[t.kind]}</span>
          <span className="min-w-0 flex-1 break-words">{t.message}</span>
          <button
            type="button"
            aria-label="Dismiss"
            onClick={() => dismiss(t.id)}
            className="shrink-0 text-current opacity-50 hover:opacity-100"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
