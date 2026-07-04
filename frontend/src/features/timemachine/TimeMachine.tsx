import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api/client";
import { LineChart } from "../../charts/LineChart";
import { Badge, ErrorNote, inputClass, Panel, qualityTone } from "../../ui/kit";

const HOUR = 3_600_000;

/** Time Machine: scrub a 24h time slider for a point-in-time snapshot, and chart a tag's range. */
export function TimeMachine({ now }: { now: number }) {
  const nodes = useQuery({ queryKey: ["nodes"], queryFn: api.listNodes });
  const [nodeId, setNodeId] = useState("");
  useEffect(() => {
    if (!nodeId && nodes.data?.length) setNodeId(nodes.data[0].id);
  }, [nodes.data, nodeId]);

  const tags = useQuery({
    queryKey: ["tags", nodeId],
    queryFn: () => api.listTags(nodeId),
    enabled: !!nodeId,
  });

  const [atMs, setAtMs] = useState(now);
  const atIso = useMemo(() => new Date(atMs).toISOString(), [atMs]);
  const snapshot = useQuery({
    queryKey: ["snapshot", nodeId, atIso],
    queryFn: () => api.snapshot(nodeId, atIso),
    enabled: !!nodeId,
    refetchInterval: false, // point-in-time history — no need for the app-wide 5s poll
  });

  const [tagKey, setTagKey] = useState("");
  useEffect(() => {
    // keep the current tag only if it belongs to the (possibly just-switched) node; else pick the first
    const keys = (tags.data ?? []).map((t) => t.canonicalKey);
    setTagKey((prev) => (keys.includes(prev) ? prev : (keys[0] ?? "")));
  }, [tags.data]);
  const range = useQuery({
    queryKey: ["range", nodeId, tagKey, now],
    queryFn: () =>
      api.range(
        nodeId,
        tagKey,
        new Date(now - 24 * HOUR).toISOString(),
        new Date(now).toISOString(),
      ),
    enabled: !!nodeId && !!tagKey,
    refetchInterval: false, // historical 24h window — static, no polling
  });

  const points = useMemo<[number, number][]>(
    () =>
      (range.data ?? [])
        .map((s) => [s.tsMillis, Number(s.value)] as [number, number])
        .filter(([, v]) => Number.isFinite(v)),
    [range.data],
  );

  const nodeSelect = (
    <select
      aria-label="node"
      className={inputClass}
      value={nodeId}
      onChange={(e) => setNodeId(e.target.value)}
    >
      {nodes.data?.map((n) => (
        <option key={n.id} value={n.id}>
          {n.name}
        </option>
      ))}
    </select>
  );

  const loadError = nodes.isError || snapshot.isError || range.isError;
  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      {loadError && (
        <ErrorNote error="Couldn't load history from the server — retrying." />
      )}
      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-3">
        <Panel
          title="Snapshot — point in time"
          actions={nodeSelect}
          bodyClassName=""
        >
          <div className="border-b border-white/5 p-4">
            <input
              type="range"
              aria-label="time"
              className="w-full accent-indigo-500"
              min={now - 24 * HOUR}
              max={now}
              step={60_000}
              value={atMs}
              onChange={(e) => setAtMs(Number(e.target.value))}
            />
            <p className="mt-2 text-xs text-zinc-500">
              {new Date(atMs).toLocaleString()}
            </p>
          </div>
          <table className="w-full text-sm">
            <tbody>
              {Object.values(snapshot.data?.values ?? {}).map((v) => (
                <tr
                  key={v.tagKey}
                  className="border-t border-white/5 hover:bg-white/5"
                >
                  <td className="px-4 py-1.5 text-zinc-300">{v.tagKey}</td>
                  <td className="px-4 py-1.5 text-right font-mono text-zinc-100">
                    {String(v.value)}
                  </td>
                  <td className="px-4 py-1.5 text-right">
                    <Badge tone={qualityTone(v.quality)}>{v.quality}</Badge>
                  </td>
                </tr>
              ))}
              {snapshot.data &&
                Object.keys(snapshot.data.values).length === 0 && (
                  <tr>
                    <td className="px-4 py-3 text-zinc-500">
                      no data at this time
                    </td>
                  </tr>
                )}
            </tbody>
          </table>
        </Panel>

        <Panel
          title="Range — last 24h"
          className="lg:col-span-2"
          actions={
            <select
              aria-label="tag"
              className={inputClass}
              value={tagKey}
              onChange={(e) => setTagKey(e.target.value)}
            >
              {tags.data?.map((t) => (
                <option key={t.id} value={t.canonicalKey}>
                  {t.canonicalKey}
                </option>
              ))}
            </select>
          }
        >
          {points.length > 0 ? (
            <LineChart
              name={tagKey}
              points={points}
              className="h-full min-h-0 w-full"
            />
          ) : (
            <p className="text-sm text-zinc-500">no samples in range</p>
          )}
        </Panel>
      </div>
    </div>
  );
}
