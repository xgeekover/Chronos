import { javascript } from "@codemirror/lang-javascript";
import { type Diagnostic, linter } from "@codemirror/lint";
import CodeMirror from "@uiw/react-codemirror";
import { useMemo, useState } from "react";
import { Badge } from "../../ui/kit";

// The Function node's JS runs as a Node-RED-style function *body* (top-level `return` is allowed and
// msg/node/flow/… are injected). Binding them as parameter names makes `new Function` parse the code
// with the exact same semantics, so valid handler code isn't falsely flagged.
const JS_PARAMS = ["msg", "node", "flow", "global", "env", "context", "RED"];

type Validation = { valid: boolean; error?: string };

/**
 * Code editor for the Function node: syntax-highlighted sandboxed JavaScript with live, client-side
 * syntax checking — the browser's own parser is an accurate proxy for GraalJS syntax validity. Drag
 * the bottom edge (or use the dialog's ⤢ expand) to enlarge.
 */
export function FunctionCodeField({
  value,
  onChange,
  height = "16rem",
}: {
  value: string;
  onChange: (v: string) => void;
  height?: string;
}) {
  const [status, setStatus] = useState<Validation | null>(null);

  const lintExt = useMemo(
    () =>
      linter(
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
      ),
    [],
  );

  const langExt = useMemo(() => javascript(), []);

  return (
    <div>
      <div className="mb-1 flex h-5 items-center gap-2 text-xs">
        <span className="uppercase tracking-wide text-zinc-500">js</span>
        {status &&
          (status.valid ? (
            <Badge tone="good">✓ valid</Badge>
          ) : (
            <Badge tone="bad">✗ {status.error}</Badge>
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
