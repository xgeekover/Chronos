import { java } from "@codemirror/lang-java";
import { javascript } from "@codemirror/lang-javascript";
import { type Diagnostic, linter } from "@codemirror/lint";
import CodeMirror from "@uiw/react-codemirror";
import { useMemo, useState } from "react";
import { api, type ScriptValidation } from "../../api/client";
import { Badge } from "../../ui/kit";

// The Function node's JS runs as a Node-RED-style function *body* (top-level `return` is allowed and
// msg/node/flow/… are injected). Binding them as parameter names makes `new Function` parse the code
// with the exact same semantics, so valid handler code isn't falsely flagged.
const JS_PARAMS = ["msg", "node", "flow", "global", "env", "context", "RED"];

/**
 * Code editor for the Function node: syntax-highlighted (JS or Java) with live syntax checking that
 * follows the selected language — a real backend Java compile for Java, a client-side parse for JS.
 * Drag the bottom edge (or use the dialog's ⤢ expand) to enlarge.
 */
export function FunctionCodeField({
  lang,
  value,
  onChange,
  height = "16rem",
}: {
  lang: "js" | "java";
  value: string;
  onChange: (v: string) => void;
  height?: string;
}) {
  const [status, setStatus] = useState<ScriptValidation | null>(null);

  const lintExt = useMemo(() => {
    if (lang === "js") {
      // Client-side: the browser's own parser is an accurate proxy for GraalJS syntax validity.
      return linter(
        (view): Diagnostic[] => {
          const code = view.state.doc.toString();
          if (!code.trim()) {
            setStatus(null);
            return [];
          }
          try {
            // eslint-disable-next-line no-new-func -- parse-only syntax check; never executed
            new Function(...JS_PARAMS, code);
            setStatus({ valid: true });
            return [];
          } catch (e) {
            const message = e instanceof Error ? e.message : "syntax error";
            setStatus({ valid: false, error: message });
            // new Function gives no reliable offset, so surface the message on the first line.
            const first = view.state.doc.line(1);
            return [
              { from: first.from, to: first.to, severity: "error", message },
            ];
          }
        },
        { delay: 400 },
      );
    }
    // Java: delegate to the real compiler on the backend (precise line + the security allow-list).
    return linter(
      async (view): Promise<Diagnostic[]> => {
        const text = view.state.doc.toString();
        if (!text.trim()) {
          setStatus(null);
          return [];
        }
        let res: ScriptValidation;
        try {
          res = await api.validateScript(text);
        } catch {
          return [];
        }
        setStatus(res);
        if (res.valid) return [];
        const lineNo = Math.min(
          Math.max(res.line ?? 1, 1),
          view.state.doc.lines,
        );
        const line = view.state.doc.line(lineNo);
        return [
          {
            from: line.from,
            to: line.to,
            severity: "error",
            message: res.error ?? "invalid script",
          },
        ];
      },
      { delay: 500 },
    );
  }, [lang]);

  const langExt = useMemo(
    () => (lang === "js" ? javascript() : java()),
    [lang],
  );

  return (
    <div>
      <div className="mb-1 flex h-5 items-center gap-2 text-xs">
        <span className="uppercase tracking-wide text-zinc-500">{lang}</span>
        {status &&
          (status.valid ? (
            <Badge tone="good">✓ valid</Badge>
          ) : (
            <Badge tone="bad">
              ✗ {status.line ? `line ${status.line}: ` : ""}
              {status.error}
            </Badge>
          ))}
      </div>
      {/* fixed initial height + resize-y so the user can drag the editor larger; CodeMirror fills it */}
      <div
        className="resize-y overflow-hidden rounded-lg border border-white/10"
        style={{ height, minHeight: "8rem" }}
      >
        <CodeMirror
          value={value}
          height="100%"
          style={{ height: "100%" }}
          theme="dark"
          extensions={[langExt, lintExt]}
          onChange={onChange}
        />
      </div>
    </div>
  );
}
