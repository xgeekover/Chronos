import { type ReactNode, useEffect, useId, useRef } from "react";

/** Small design-system kit so views share a consistent, modern look. */

export function Card({
  title,
  actions,
  children,
  className = "",
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-xl border border-white/5 bg-zinc-900/60 shadow-lg shadow-black/20 backdrop-blur ${className}`}
    >
      {(title || actions) && (
        <header className="flex items-center justify-between border-b border-white/5 px-4 py-3">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-zinc-400">
            {title}
          </h2>
          {actions}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

/**
 * Full-height panel for single-screen layouts: a fixed header + an internally-scrolling body, so the
 * page itself never scrolls. Drop it into a height-constrained grid/flex cell (`min-h-0`).
 */
export function Panel({
  title,
  actions,
  children,
  bodyClassName = "p-4",
  className = "",
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  bodyClassName?: string;
  className?: string;
}) {
  return (
    <section
      className={`flex h-full min-h-0 flex-col overflow-hidden rounded-xl border border-white/5 bg-zinc-900/60 shadow-lg shadow-black/20 backdrop-blur ${className}`}
    >
      {(title || actions) && (
        <header className="flex shrink-0 items-center justify-between gap-2 border-b border-white/5 px-4 py-2.5">
          <h2 className="truncate text-xs font-semibold uppercase tracking-wider text-zinc-400">
            {title}
          </h2>
          {actions}
        </header>
      )}
      <div className={`min-h-0 flex-1 overflow-auto ${bodyClassName}`}>
        {children}
      </div>
    </section>
  );
}

type Tone = "good" | "bad" | "warn" | "info" | "muted";

const TONES: Record<Tone, string> = {
  good: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30",
  bad: "bg-rose-500/15 text-rose-300 ring-rose-500/30",
  warn: "bg-amber-500/15 text-amber-300 ring-amber-500/30",
  info: "bg-sky-500/15 text-sky-300 ring-sky-500/30",
  muted: "bg-zinc-500/15 text-zinc-400 ring-zinc-500/30",
};

export function Badge({
  tone = "muted",
  children,
}: {
  tone?: Tone;
  children: ReactNode;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${TONES[tone]}`}
    >
      {children}
    </span>
  );
}

export function qualityTone(q?: string): Tone {
  switch (q) {
    case "GOOD":
      return "good";
    case "BAD":
      return "bad";
    case "STALE":
    case "UNCERTAIN":
      return "warn";
    default:
      return "muted";
  }
}

// accent is a full literal class (Tailwind only generates classes it sees as complete strings).
export function StatCard({
  label,
  value,
  accent = "bg-indigo-500/70",
}: {
  label: string;
  value: ReactNode;
  accent?: string;
}) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-white/5 bg-zinc-900/60 p-4 shadow-lg shadow-black/20">
      <div className={`absolute inset-x-0 top-0 h-0.5 ${accent}`} />
      <div className="text-[11px] font-medium uppercase tracking-wider text-zinc-500">
        {label}
      </div>
      <div className="mt-1 text-2xl font-semibold text-zinc-100">{value}</div>
    </div>
  );
}

export function Button({
  children,
  onClick,
  variant = "primary",
  size = "md",
  disabled,
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "ghost" | "danger" | "deploy";
  size?: "md" | "sm";
  disabled?: boolean;
  type?: "button" | "submit";
}) {
  const styles = {
    primary: "bg-indigo-600 text-white hover:bg-indigo-500",
    ghost: "bg-white/5 text-zinc-300 hover:bg-white/10",
    danger: "bg-rose-600/90 text-white hover:bg-rose-500",
    // Node-RED signature red — used for the pipeline compile/deploy action
    deploy: "bg-[#AD1625] text-white shadow hover:bg-[#c81e2f]",
  }[variant];
  const dims =
    size === "sm"
      ? "rounded-lg px-2 py-1 text-[11px]"
      : "rounded-lg px-3 py-1.5 text-xs";
  return (
    <button
      type={type}
      disabled={disabled}
      onClick={onClick}
      className={`${dims} font-medium transition-colors disabled:opacity-40 ${styles}`}
    >
      <span className="inline-flex items-center justify-center gap-1.5">
        {children}
      </span>
    </button>
  );
}

/** Inline 24×24 stroke icon (no icon dependency). Pass an SVG path `d`. */
export function Icon({
  path,
  className = "h-3.5 w-3.5",
}: {
  path: string;
  className?: string;
}) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      className={className}
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d={path} />
    </svg>
  );
}

/**
 * Accessible modal dialog: `role="dialog"` + `aria-modal`, labelled by its title, closes on Escape or
 * backdrop click, traps Tab within the panel, and restores focus to the trigger on close. Replaces the
 * hand-rolled `fixed inset-0` shells so every dialog is keyboard-operable and screen-reader-correct.
 */
export function Modal({
  title,
  onClose,
  children,
  className = "",
}: {
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  // read the latest onClose without making it an effect dependency — otherwise callers passing an inline
  // arrow re-run the effect on every parent render (e.g. a 1.5s poll), stealing focus back into the dialog.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const restore = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    const focusables = () =>
      panel
        ? [
            ...panel.querySelectorAll<HTMLElement>(
              'a[href],button:not([disabled]),textarea,input:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])',
            ),
          ].filter((el) => el.offsetParent !== null)
        : [];
    (focusables()[0] ?? panel)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (e.key === "Tab" && panel) {
        const items = focusables();
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      restore?.focus?.();
    };
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <button
        type="button"
        aria-label="Close"
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`relative max-h-[90vh] overflow-auto rounded-xl border border-white/10 bg-zinc-900 p-5 shadow-2xl outline-none ${className}`}
      >
        <h3 id={titleId} className="mb-1 font-semibold text-zinc-100">
          {title}
        </h3>
        {children}
      </div>
    </div>
  );
}

export const inputClass =
  "rounded-lg border border-white/10 bg-zinc-950/60 px-2.5 py-1.5 text-sm text-zinc-100 outline-none focus:border-indigo-500/60 focus:ring-1 focus:ring-indigo-500/40";

export function Spinner({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`h-4 w-4 animate-spin text-indigo-400 ${className}`}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle
        className="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth="4"
      />
      <path
        className="opacity-90"
        fill="currentColor"
        d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4z"
      />
    </svg>
  );
}

/** Consistent loading / error / empty placeholders so every view feels finished. */
export function Loading({ label = "loading…" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-6 text-sm text-zinc-500">
      <Spinner />
      {label}
    </div>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <div className="flex items-start gap-2 rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-sm text-rose-300">
      <span aria-hidden="true">⚠</span>
      <span className="min-w-0 break-words">{msg}</span>
    </div>
  );
}

export function Empty({ label = "nothing here yet" }: { label?: string }) {
  return <div className="py-6 text-sm text-zinc-500">{label}</div>;
}

/**
 * Render-helper for a TanStack Query result: shows Loading while pending, ErrorNote on error,
 * Empty when the resolved value is an empty array, otherwise the children.
 */
export function QueryState<T>({
  query,
  children,
  empty,
}: {
  query: {
    isPending: boolean;
    isError: boolean;
    error: unknown;
    data: T | undefined;
  };
  children: (data: T) => ReactNode;
  empty?: string;
}) {
  if (query.isPending) return <Loading />;
  if (query.isError) return <ErrorNote error={query.error} />;
  const data = query.data as T;
  if (Array.isArray(data) && data.length === 0) return <Empty label={empty} />;
  return <>{children(data)}</>;
}
