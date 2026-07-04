import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, canOperate, isAdmin } from "../../api/client";
import { Badge, Button, ErrorNote, inputClass, Panel } from "../../ui/kit";

function deviceTone(s: string) {
  if (s === "OK" || s === "CONFIGURED") return "good" as const;
  if (s === "ERROR") return "bad" as const;
  return "muted" as const;
}

const PULL_ADAPTERS = ["JDBC", "MQTT", "MODBUS", "TCP", "FILE", "SHELL"];

/**
 * Devices — manage globally-registered data sources, their scheduled collection tasks, and the live
 * connection pools. A device is registered once here; flow "device read" nodes reference it so every
 * node/task sharing a source shares one bounded connection pool (no per-node DB session sprawl).
 */
export function Devices() {
  const qc = useQueryClient();
  const admin = isAdmin();
  const devices = useQuery({ queryKey: ["devices"], queryFn: api.listDevices });
  const tasks = useQuery({ queryKey: ["tasks"], queryFn: api.listTasks });
  const pools = useQuery({
    queryKey: ["pools"],
    queryFn: api.devicePools,
    refetchInterval: 3000,
    refetchIntervalInBackground: true, // keep monitoring even when the tab isn't focused
  });
  const deviceName = (id: string) =>
    devices.data?.find((d) => d.id === id)?.name ?? id.slice(0, 8);

  // new-device form
  const [name, setName] = useState("");
  const [adapterType, setAdapterType] = useState("JDBC");
  const [jdbcUrl, setJdbcUrl] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [maxPool, setMaxPool] = useState("4");
  const [paramsJson, setParamsJson] = useState("{}");
  const [secretsJson, setSecretsJson] = useState("{}");

  const create = useMutation({
    mutationFn: () => {
      const body =
        adapterType === "JDBC"
          ? {
              name: name.trim(),
              type: "DATABASE",
              adapterType,
              params: {
                jdbcUrl: jdbcUrl.trim(),
                maxPoolSize: Number(maxPool) || 4,
              },
              secrets: { username, password },
            }
          : {
              name: name.trim(),
              type: "HOST",
              adapterType,
              params: JSON.parse(paramsJson || "{}"),
              secrets: JSON.parse(secretsJson || "{}"),
            };
      return api.createDevice(body);
    },
    onSuccess: () => {
      setName("");
      setJdbcUrl("");
      setUsername("");
      setPassword("");
      qc.invalidateQueries({ queryKey: ["devices"] });
    },
  });

  const test = useMutation({
    mutationFn: (id: string) => api.validateDevice(id),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ["devices"] });
      qc.invalidateQueries({ queryKey: ["pools"] }); // a test opens the pool → show it at once
    },
  });
  const del = useMutation({
    mutationFn: (id: string) => api.deleteDevice(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["devices"] }),
  });
  const run = useMutation({
    mutationFn: (id: string) => api.runTask(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["values"] });
      qc.invalidateQueries({ queryKey: ["pools"] });
    },
  });
  const delTask = useMutation({
    mutationFn: (id: string) => api.deleteTask(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["tasks"] }),
  });

  const jdbc = adapterType === "JDBC";
  const canCreate =
    admin && name.trim() !== "" && (!jdbc || jdbcUrl.trim() !== "");

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      {(devices.isError || tasks.isError) && (
        <ErrorNote error="Couldn't reach the server — retrying." />
      )}
      <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-3">
        {/* left (span 2): registered devices + scheduled tasks */}
        <div className="grid min-h-0 gap-4 lg:col-span-2 lg:grid-rows-2">
          <Panel title={`Devices · ${devices.data?.length ?? 0}`}>
            <ul className="text-sm">
              {devices.data?.map((d) => (
                <li
                  key={d.id}
                  className="flex items-center justify-between gap-2 border-b border-white/5 py-1.5"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-medium text-zinc-200">
                        {d.name}
                      </span>
                      <Badge tone="info">{d.adapterType}</Badge>
                      <Badge tone={deviceTone(d.status)}>{d.status}</Badge>
                    </div>
                    <div className="truncate font-mono text-[11px] text-zinc-500">
                      {String(d.connectionConfigMeta?.jdbcUrl ?? d.type)}
                    </div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={!admin || test.isPending}
                      onClick={() => test.mutate(d.id)}
                    >
                      Test
                    </Button>
                    <Button
                      size="sm"
                      variant="danger"
                      disabled={!admin}
                      onClick={() => del.mutate(d.id)}
                    >
                      ✕
                    </Button>
                  </div>
                </li>
              ))}
              {!devices.data?.length && (
                <li className="py-2 text-zinc-500">no devices registered</li>
              )}
            </ul>
          </Panel>

          <Panel
            title={`Scheduled collection tasks · ${tasks.data?.length ?? 0}`}
          >
            <ul className="text-sm">
              {tasks.data?.map((t) => (
                <li
                  key={t.id}
                  className="flex items-center justify-between gap-2 border-b border-white/5 py-1.5"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <Badge tone="info">{t.type}</Badge>
                      <span className="truncate text-zinc-300">
                        {deviceName(t.deviceId)}
                      </span>
                    </div>
                    <div className="text-[11px] text-zinc-500">
                      {t.scheduleKind}
                      {t.intervalMs ? ` · every ${t.intervalMs}ms` : ""}
                      {t.enabled ? "" : " · disabled"}
                    </div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={!canOperate()}
                      onClick={() => run.mutate(t.id)}
                    >
                      Run
                    </Button>
                    <Button
                      size="sm"
                      variant="danger"
                      disabled={!admin}
                      onClick={() => delTask.mutate(t.id)}
                    >
                      ✕
                    </Button>
                  </div>
                </li>
              ))}
              {!tasks.data?.length && (
                <li className="py-2 text-zinc-500">no scheduled tasks</li>
              )}
            </ul>
          </Panel>
        </div>

        {/* right: live pools + new-device form */}
        <div className="grid min-h-0 gap-4 lg:grid-rows-2">
          <Panel
            title={`Connection pools · ${pools.data?.length ?? 0}`}
            actions={
              <span className="h-2 w-2 animate-pulse rounded-full bg-emerald-500" />
            }
          >
            <ul className="space-y-2 text-xs">
              {pools.data?.map((p) => (
                <li
                  key={`${p.url}|${p.user}`}
                  className="rounded-md border border-white/5 bg-zinc-950/40 p-2"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-mono text-zinc-300">
                      {p.url}
                    </span>
                    <span className="shrink-0 text-zinc-500">{p.user}</span>
                  </div>
                  <div className="mt-1.5 flex items-center gap-2">
                    <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-white/10">
                      <div
                        className="h-full rounded-full bg-indigo-500"
                        style={{
                          width: `${Math.min(100, (p.active / Math.max(1, p.max)) * 100)}%`,
                        }}
                      />
                    </div>
                    <span className="shrink-0 text-zinc-400">
                      {p.active} active · {p.total}/{p.max}
                    </span>
                    {p.awaiting > 0 && (
                      <Badge tone="warn">{p.awaiting} waiting</Badge>
                    )}
                  </div>
                </li>
              ))}
              {!pools.data?.length && (
                <li className="leading-relaxed text-zinc-500">
                  No active DB pools. A pool opens on the first query and is
                  shared by every node/task using the same URL + user, bounded
                  by the device's max pool size.
                </li>
              )}
            </ul>
          </Panel>

          <Panel title="New device">
            <div className="flex flex-col gap-2 text-sm">
              <input
                className={inputClass}
                placeholder="name e.g. plant-db"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
              <select
                aria-label="adapter type"
                className={inputClass}
                value={adapterType}
                onChange={(e) => setAdapterType(e.target.value)}
              >
                {PULL_ADAPTERS.map((a) => (
                  <option key={a}>{a}</option>
                ))}
              </select>
              {jdbc ? (
                <>
                  <input
                    className={`${inputClass} font-mono`}
                    placeholder="jdbcUrl e.g. jdbc:postgresql://host:5432/db"
                    value={jdbcUrl}
                    onChange={(e) => setJdbcUrl(e.target.value)}
                  />
                  <div className="flex gap-2">
                    <input
                      className={`${inputClass} min-w-0 flex-1`}
                      placeholder="username"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                    />
                    <input
                      aria-label="max pool size"
                      type="number"
                      className={`${inputClass} w-24`}
                      placeholder="max pool"
                      value={maxPool}
                      onChange={(e) => setMaxPool(e.target.value)}
                    />
                  </div>
                  <input
                    className={inputClass}
                    type="password"
                    placeholder="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </>
              ) : (
                <>
                  <textarea
                    className={`${inputClass} h-16 resize-y font-mono`}
                    placeholder='params JSON e.g. {"host":"...","port":1883}'
                    value={paramsJson}
                    onChange={(e) => setParamsJson(e.target.value)}
                  />
                  <textarea
                    className={`${inputClass} h-16 resize-y font-mono`}
                    placeholder='secrets JSON e.g. {"username":"","password":""}'
                    value={secretsJson}
                    onChange={(e) => setSecretsJson(e.target.value)}
                  />
                </>
              )}
              <Button disabled={!canCreate} onClick={() => create.mutate()}>
                Register device
              </Button>
              {create.isError && (
                <p className="text-xs text-rose-400">
                  {(create.error as Error).message}
                </p>
              )}
              <p className="text-[11px] leading-relaxed text-zinc-500">
                Register a source once; flow{" "}
                <span className="font-mono">device read</span> nodes reference
                it → one shared, bounded connection pool.
              </p>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}
