import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { Badge, Card, QueryState } from "../../ui/kit";

/** Collection-log view (§10): recent task runs with status/duration/error. */
export function Logs() {
  const logs = useQuery({
    queryKey: ["logs"],
    queryFn: api.logs,
    refetchInterval: 5000,
  });

  return (
    <Card title={`Recent runs · ${logs.data?.length ?? 0}`}>
      <QueryState query={logs} empty="no collection runs yet">
        {(rows) => (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-[11px] uppercase tracking-wider text-zinc-500">
                <th className="pb-2 font-medium">Started</th>
                <th className="pb-2 font-medium">Status</th>
                <th className="pb-2 text-right font-medium">ms</th>
                <th className="pb-2 text-right font-medium">tags</th>
                <th className="pb-2 pl-3 font-medium">Error</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((l) => (
                <tr key={l.id} className="border-t border-white/5">
                  <td className="py-1.5 text-zinc-300">
                    {new Date(l.startedAt).toLocaleString()}
                  </td>
                  <td className="py-1.5">
                    <Badge tone={l.status === "OK" ? "good" : "bad"}>
                      {l.status}
                    </Badge>
                  </td>
                  <td className="py-1.5 text-right font-mono text-zinc-400">
                    {l.durationMs ?? "-"}
                  </td>
                  <td className="py-1.5 text-right font-mono text-zinc-400">
                    {l.tagCount ?? "-"}
                  </td>
                  <td className="max-w-xs truncate py-1.5 pl-3 text-zinc-500">
                    {l.error ?? ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </QueryState>
    </Card>
  );
}
