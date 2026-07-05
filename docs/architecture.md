# Chronos — Architecture & ADRs

Plugin-based industrial IoT data collection & historian platform. This document captures
the architecture overview, the invariant rules, and the Architecture Decision Records
agreed in Phase 0.

## Overview

```
[ Frontend (React/Vite) ]  ──REST(OpenAPI) + WebSocket──▶  [ Backend (Spring Boot 4 / Java 21) ]
                                                                │
        ┌───────────────────────┬───────────────────┬─────────┴───────────┐
        ▼                       ▼                   ▼                       ▼
   [ Scheduler ]        [ Collection Engine ]  [ Plugin Runtime ]   [ External Gateway ]
    (Quartz)             Task→Adapter→Parser     (PF4J adapters)     (WebSocket + protobuf)
                              →TagValue                                      │
        ┌──────────────────────┼─────────────────┐                         ▼
        ▼                      ▼                 ▼              [ .NET / Java / Python SDK ]
 [ Current-value cache ]  [ Node-local TS DB ]  [ WS broadcast ]
  (real-time dashboard)    (Time Machine, SQLite/node)

 [ Platform metadata DB ] = Device/Node/Tag/Task/Flow/Audit config (PostgreSQL)
```

## Invariant rules

1. **Backend and Frontend are physically separate**, independently deployed/scaled. FE→BE is
   REST + WebSocket with CORS + token auth.
2. **The core depends only on the SPI (extension points).** Core modules never import a
   concrete adapter. Adding a new protocol = adding a new plugin module only.
3. The **collect → normalize → store/broadcast** pipeline is identical regardless of adapter
   kind. Adapters only produce a raw result; mapping/storage/distribution belong to the core.
4. **Credentials/secrets are never stored in plaintext and never logged.**

## Module layout & dependency direction

| Module | Role | Depends on |
|--------|------|-----------|
| `core-api` | SPI + immutable value objects (framework-free except PF4J marker) | — |
| `core-engine` | pipeline, plugin runtime, history store, current-value cache | `core-api` |
| `app` | Spring Boot: REST/WS, metadata persistence, security, wiring | `core-engine` |
| `gateway` | external WS + protobuf gateway for the SDKs | `core-engine` |
| `adapters/adapter-*` | protocol plugins (jdbc/file/shell/api/mqtt/modbus/tcp) | **`core-api` only** |

Adapters must never depend on `core-engine`/`app` (invariant rule 2). This is what keeps
"drop a JAR to add a protocol" true.

## ADRs

| # | Decision | Rationale | Trade-off / alternative |
|---|----------|-----------|-------------------------|
| 001 | **PF4J** runtime plugin loading; `core-api` has a lightweight `org.pf4j.ExtensionPoint` dependency. Develop/build/run on **OpenJDK 17** (project constraint) via the Gradle toolchain. | Runtime JAR drop-in + classloader isolation is the project's core value. Spring Boot 4 / Framework 7 baseline is Java 17, so the stack runs on 17 unchanged. | `core-api` isn't 100% framework-free. Alt: `ServiceLoader` (pure but weak isolation/hot-load). |
| 002 | **FE/BE physically separate**; Gradle build covers backend only, frontend builds via pnpm. | Invariant rule 1; independent deploy/scale. | Two toolchains, two CI jobs. |
| 003 | Scheduler = **Quartz**, per-Task interval/cron, dynamic register/unregister. Phase 2 uses the in-memory job store + startup re-scheduling from the metadata DB. | Per-Task dynamic scheduling. | In-memory store isn't clustered; switch to the JDBC JobStore when multi-instance is needed. |
| 004 | Time Machine = **SQLite file per node** (WAL) behind the `HistoryStore` SPI. | Node isolation, portability, simple retention. | Single-writer per node. Alt: DuckDB (analytics) / RocksDB (write throughput). |
| 005 | Dynamic code in flows = **sandboxed GraalVM JavaScript** (`HostAccess.NONE`) in the Function node. | One scripting language; a genuine sandbox, not a blocklist. | Superseded the earlier in-process Java (Java Function option + `SCRIPT_JAVA` adapter), which were **removed**. |
| 006 | Shell = **Apache MINA SSHD** (remote) + `ProcessBuilder` (local), command allowlist + argument separation + timeout. | Invariant + injection safety. | allowlist operational burden. |
| 007 | External protocol = **Protobuf over WebSocket**, per-frame **LZ4 (default) / gzip**. | multi-language codegen, compact payloads. | proto evolution discipline (immutable field numbers). |
| 008 | Credentials = **AES-GCM (256)** with DEK/KEK envelope; KEK externalized (env/Vault). | no plaintext, key rotation. | env-only key is an operational risk. |
| 009 | Metadata DB = **PostgreSQL** (default) + **Flyway**; ORM = **Spring Data JPA**, complex queries via jOOQ/native. | productivity + migrations. | `jsonb` mapping needs custom types. |
| 010 | Collection concurrency = **bounded platform-thread pool** + per-device semaphore + HikariCP pool + circuit breaker. (Virtual threads are unavailable on Java 17 — revisit if the JDK is later raised to 21.) | I/O-bound mass collection. | platform threads cost more memory than virtual threads at very high task counts; mitigate with a tuned pool + backpressure. |
| 011 | Current-value cache = **in-memory** (single backend instance assumption). | lowest latency. | multi-instance needs Redis (same shape behind the cache). |
| 012 | Tag addressing = hierarchical dotted key `node.segment…tag`. | SDK looks up by tag name only. | tag names must not contain `.`. |
| 013 | Quality flags = **GOOD / BAD / STALE / UNCERTAIN** (OPC-UA inspired). | express mapping failure/staleness. | small storage overhead. |

## Phase status

- **Phase 0** — design (this document). ✅
- **Phase 1** — scaffolding: Gradle multi-module, Flyway metadata schema, Spring Boot boot,
  React app boot, CI. ✅ (build + tests + demo green)
- **Phase 2** — collection engine + JDBC adapter E2E: Device/Node/Tag/Task/Mapping CRUD (REST +
  minimal UI), Quartz scheduling, ServiceLoader plugin runtime, real JDBC adapter (HikariCP),
  default parser (COLUMN+transform), current-value cache, per-node SQLite history. ✅ verified
  E2E against real **MariaDB + Oracle + MS-SQL** + PostgreSQL (Testcontainers). The Oracle/
  MS-SQL E2E tests are tagged `heavydb` and excluded from the default `test`; run them with
  `./gradlew heavyDbTest` (also a dedicated CI step).
- **Phase 3** — Time Machine: per-node retention cleanup job (`@Scheduled`), point-in-time
  snapshot + time-range query API (`GET /api/nodes/{id}/snapshot|range`), and a UI Time Machine
  view (24h time slider → snapshot, tag range → ECharts line). ✅ verified (tests + live demo:
  snapshot at a past instant returns the historical value, not the latest).
- **Phase 4** — remaining adapters + PF4J plugins: `adapter-file`, `adapter-api` (JDK HttpClient),
  `adapter-shell` (local ProcessBuilder + remote MINA SSHD; allowlist + arg-array + timeout +
  output cap). Parser gained
  JSONPATH/REGEX extractors. All adapters are `@Extension` PF4J plugins (generated
  `extensions.idx`) and ServiceLoader built-ins; the app merges built-ins + dropped plugin JARs
  via `CompositePluginRuntime` (`chronos.plugins.dir`). ✅ core-unmodified drop-in proven by
  `Pf4jPluginRuntimeTest` (loads an adapter from a JAR placed in a dir); all adapters discovered
  at boot. Production fat-JAR packaging documented in `adding-a-protocol.md`.
- **Phase 5** — Gateway + SDKs: single `proto/chronos.proto` schema; a WebSocket gateway
  (`/ws/gateway`) speaking protobuf with **token handshake auth** and **gzip framing**, wired to
  the pipeline broadcast + Time-Machine snapshots (`CompositePluginRuntime`→`GatewayBroadcaster`).
  **Java SDK** (subscribe / getValue / getSnapshot by tag name) verified E2E against the live
  gateway; **Python SDK** verified (codec tests + live push demo); **.NET SDK** delivered as
  source (no local toolchain). Each SDK has a 5-line Quickstart. ✅
- **Phase 6** — dashboard / logs: per-run `collection_log` persistence + logs API; dashboard
  summary (inventory counts + collection success rate + live-value count). FE gains a Dashboard
  and a Logs view. ✅ verified by tests + a live demo (collection run → logged → dashboard).
- **Phase 7** — hardening / deploy: **Micrometer + Prometheus** metrics (collections,
  gateway subscriptions) at `/actuator/prometheus`; **Spring Security** HTTP Basic over `/api/**`
  (health/prometheus + gateway WS open); **docker-compose** packaging (postgres + backend image +
  frontend nginx with `/api`,`/ws` proxy, healthchecks, graceful shutdown); a concurrent
  **load smoke test**. ✅ verified by tests + the full packaged stack running. Security review +
  checklist in `docs/security.md`.

## North-Star demo ✅

Verified end-to-end against the packaged Docker stack: a MariaDB sample table → a `QUERY` Task
`SELECT temp_c, humidity, pressure` → 3 Tag mappings → real-time dashboard current values →
Time-Machine snapshot reconstructs all three → the **Java SDK receives them by tag name**
(`plantNS.temperature=21.7`, `humidity=48.0`, `pressure=1013.2`). All 7 phases complete.
