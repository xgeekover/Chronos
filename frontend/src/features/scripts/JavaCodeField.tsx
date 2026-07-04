import { java } from "@codemirror/lang-java";
import { type Diagnostic, linter } from "@codemirror/lint";
import CodeMirror from "@uiw/react-codemirror";
import { useMemo, useState } from "react";
import { api, type ScriptValidation } from "../../api/client";
import { Badge } from "../../ui/kit";

/** Reusable pure-Java editor with live backend syntax/security validation (status + gutter markers). */
export function JavaCodeField({
  value,
  onChange,
  height = "220px",
}: {
  value: string;
  onChange: (v: string) => void;
  height?: string;
}) {
  const [status, setStatus] = useState<ScriptValidation | null>(null);

  const lintExtension = useMemo(
    () =>
      linter(
        async (view): Promise<Diagnostic[]> => {
          const text = view.state.doc.toString();
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
      ),
    [],
  );

  return (
    <div>
      <div className="mb-1 h-5 text-xs">
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
      <div className="overflow-hidden rounded-lg border border-white/10">
        <CodeMirror
          value={value}
          height={height}
          theme="dark"
          extensions={[java(), lintExtension]}
          onChange={onChange}
        />
      </div>
    </div>
  );
}
