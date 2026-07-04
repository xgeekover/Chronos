# Chronos

**Plugin-based industrial IoT data-collection & historian platform.**

Chronos periodically collects from diverse industrial sources (SQL databases, files, shell commands,
MQTT, Modbus, TCP, HTTP, Java scripts), normalizes everything into per-node **Tag(Key)–Value**
pairs, stores a per-node local time series (**Time Machine**), and lets external programs subscribe
to live values over a **WebSocket + protobuf gateway** with Java / Python / .NET SDKs.

Data flows are authored visually in a **Node-RED-style flow editor** — drag nodes from a categorized
palette, wire them, and deploy. New protocols are added as **PF4J plugins** without touching the core.

> Design & rationale: [`docs/architecture.md`](docs/architecture.md) ·
> Extending it: [`docs/adding-a-protocol.md`](docs/adding-a-protocol.md) ·
> Security posture: [`docs/security.md`](docs/security.md)

---

## Table of contents

- [What you get](#what-you-get)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Quick start (Docker)](#quick-start-docker)
- [Local development](#local-development)
- [Configuration](#configuration)
- [Ports](#ports)
- [Usage walkthrough](#usage-walkthrough)
- [Project layout](#project-layout)
- [Build & test](#build--test)
- [Security](#security)

---

## What you get

A web admin UI at **http://localhost:8088** with these views:

- **Dashboard** — live KPIs (success rate, collections, live-value count, device count), device /
  node / task inventory, and a live current-values table (auto-refresh every 5 s). Single-screen
  layout with per-panel scrolling.
- **Flows** — a Node-RED-style visual editor (React Flow) and the primary authoring surface. Drag
  nodes from a categorized palette (**Common / Function / Network / Sequence / Parser / Storage**),
  wire them, double-click to configure, and **Deploy** to run. Nodes include:
  - *inputs*: `inject`, `http in`, `mqtt in`, `tcp in`, `udp in`, `websocket in`, `file in`
  - *function*: `Function (JS/Java)`, `switch`, `change`, `range`, `template`, `delay`, `trigger`,
    `filter (rbe)`, `exec`
  - *network*: `http request`, `soap`, and the `*out` senders (mqtt / tcp / udp / websocket)
  - *sequence*: `split`, `join`, `sort`, `batch`
  - *parser*: `csv`, `json`, `xml`, `yaml`, `html`
  - *storage*: `file out`, `tag` (writes a value into the historian), `device read` (runs any pull
    adapter — JDBC/Modbus/shell/… — mid-flow)

  A right slide-out **Inspector** streams live debug messages (with a collapsible JSON tree), flow
  context variables, per-node info, and pipeline health.
- **Tags** — an Obsidian-style folder tree (tag names split on `/`) with live values; drag a tag
  between folders; create storage nodes (retention groups) and tags.
- **Time Machine** — a point-in-time snapshot slider plus a 24 h range chart (ECharts) over the
  per-node history store.
- **Logs** — collection-run history.

The **Function** node runs sandboxed JavaScript (GraalVM, Node-RED-style `msg`/`node`/`flow`/
`global`/`env`) **or** Java (compiled in-memory), with live per-language syntax checking in a
CodeMirror editor.

The frontend is **code-split** — heavy views (flow canvas, charts, code editor) load on demand.

---

## Architecture

Chronos is a **Gradle multi-module** backend plus a React SPA, wired together only over REST + WS.

```
                 ┌──────────────────────────────────────────────┐
   Browser ───►  │  frontend (nginx)  ── /api ─┐  ── /ws ─┐      │
                 └──────────────────────────────┼──────────┼─────┘
                                                ▼          ▼
                 ┌─────────────────────────────────────────────────────────┐
                 │  app (Spring Boot)                                       │
                 │   REST API · JWT/RBAC security · Quartz scheduler        │
                 │   Flow runtime  ·  WebSocket gateway (protobuf)          │
                 │   ┌──────────────┐  ┌───────────────┐  ┌──────────────┐  │
                 │   │ core-engine  │  │  core-flow    │  │  PF4J plugins│  │
                 │   │ collection   │  │ flow graph +  │  │  adapter-*   │  │
                 │   │ pipeline +   │  │ node runtime  │  │  (protocols) │  │
                 │   │ history store│  └───────────────┘  └──────────────┘  │
                 │   └──────┬───────┘                                       │
                 └──────────┼─────────────────────────────────────────────-┘
                            ▼
           PostgreSQL (metadata + flows)  ·  SQLite per-node (time series)
```

**Modules**

| Module | Responsibility |
|--------|----------------|
| `core-api` | Pure-JDK SPI: `DeviceAdapter`, `TagValue`, `HistoryStore`, plugin contracts. |
| `core-engine` | Collection pipeline (adapter → parse → ingest), current-value cache, SQLite history store, tag broadcast. |
| `core-flow` | Framework-free flow runtime: `FlowGraph`, `FlowNode` SPI, single-threaded worker, all node implementations. |
| `app` | Spring Boot: REST controllers, JWT/RBAC security, Quartz scheduling, flow deploy/runtime manager, WebSocket gateway host, Flyway/JPA persistence. |
| `gateway` | Framework-free WebSocket + protobuf broadcaster (token auth, gzip framing, per-tag subscriptions). |
| `adapters/adapter-*` | PF4J protocol plugins — `jdbc`, `file`, `shell`, `script`, `mqtt`, `modbus`, `tcp` (+ `adapter-api` contract). |

**Storage split** — metadata (devices, nodes, tags, tasks, mappings, users, saved flows) lives in
**PostgreSQL** (schema managed by **Flyway**); the time-series history for each node is a local
**SQLite** database under `CHRONOS_HISTORY_DIR` (the "Time Machine").

---

## Tech stack

**Backend**

- **OpenJDK 17** (Gradle toolchain-pinned) · **Kotlin-DSL Gradle** multi-module build
- **Spring Boot 4.0.7** (Spring Framework 7) — REST, Security, Data JPA, Quartz, WebSocket
- **PostgreSQL 16** + **Flyway** migrations · **SQLite** per-node time series
- **PF4J 3.15** — protocol adapters as hot-pluggable plugins
- **GraalVM JS (`js-community` 24.1.1)** — sandboxed JavaScript Function node (`HostAccess.NONE`)
- **Eclipse Compiler (ECJ)** — in-memory Java compile for the Java Function node validation
- **Eclipse Paho** (MQTT) · **Jackson** (JSON) · **SnakeYAML** · **jsoup** (HTML) · **JGit** (flow versioning)
- **protobuf** + WebSocket for the streaming gateway
- **JWT (HS256)** auth · **AES-GCM** encryption of secrets at rest
- **Micrometer / Prometheus** metrics · **OpenTelemetry → Jaeger** tracing (opt-in)

**Frontend**

- **React 19** + **TypeScript** + **Vite** · **pnpm**
- **Tailwind CSS v4** (design system in `ui/kit.tsx`)
- **@xyflow/react (React Flow) 12** — the flow canvas
- **TanStack Query 5** — server state / polling
- **CodeMirror 6** (`@uiw/react-codemirror`, `lang-java`, `lang-javascript`, `lint`) — the Function-node editor
- **Apache ECharts 6** — time-series charts
- **Biome** (lint/format) · **Vitest** + Testing Library (tests)

**Infra**

- **Docker Compose** — postgres · mosquitto (MQTT) · backend · frontend (nginx) · otel-collector · jaeger
- **SDKs**: `sdk/sdk-java`, `sdk/sdk-python`, `sdk/sdk-dotnet` (subscribe to tags over the gateway)

---

## Quick start (Docker)

The fastest path — builds the backend jar, then brings up the whole stack:

```bash
./gradlew :app:bootJar        # build the Spring Boot jar
docker compose up --build     # postgres + mosquitto + backend (:8080) + frontend (:8088)
```

Then open **http://localhost:8088** and sign in.

**Demo accounts** (JWT-backed):

| User | Password | Role |
|------|----------|------|
| `admin` | `admin` | ADMIN |
| `operator` | `operator` | OPERATOR |
| `viewer` | `viewer` | VIEWER |

Gateway subscription token (SDKs): `chronos-dev-token`.

> ⚠️ These are development defaults. Override every secret via env vars (see
> [Configuration](#configuration)) before any real deployment.

### Observability (opt-in)

Prometheus metrics are always exposed at `/actuator/prometheus`. Full OpenTelemetry tracing
(OTLP → Collector → Jaeger) is behind a profile:

```bash
OTEL_ENABLED=true docker compose --profile observability up -d --build
# Jaeger UI: http://localhost:16686   (service "chronos")
```

---

## Local development

**Prerequisites:** OpenJDK 17, Node 24+ with Corepack (for pnpm), Docker (for Postgres + the
Testcontainers-based tests).

Run the backend against a local Postgres:

```bash
docker compose up -d postgres           # metadata DB on :5432
./gradlew :app:bootRun                  # Spring Boot app on :8080

curl localhost:8080/actuator/health     # -> {"status":"UP"}
curl localhost:8080/api/info            # -> {"name":"chronos", ...}
```

Run the frontend dev server (hot reload; proxies `/api` and `/ws` to `:8080`):

```bash
cd frontend
corepack enable
pnpm install
pnpm dev                                # http://localhost:5173
```

---

## Configuration

Everything is environment-driven. Key variables (see `docker-compose.yml` for the full set and
their development defaults):

| Variable | Purpose |
|----------|---------|
| `CHRONOS_DB_URL` / `CHRONOS_DB_USER` / `CHRONOS_DB_PASSWORD` | PostgreSQL metadata database. |
| `CHRONOS_API_USER` / `CHRONOS_API_PASSWORD` | Bootstrap admin account. |
| `CHRONOS_JWT_SECRET` | HS256 signing secret (**≥ 32 bytes**; stable so JWTs survive restarts). |
| `CHRONOS_SECRET_KEY` | AES key that encrypts device/flow secrets at rest (base64, 32 bytes). |
| `CHRONOS_GATEWAY_TOKEN` | Token the WebSocket/SDK clients present to subscribe. |
| `CHRONOS_HISTORY_DIR` | Directory for the per-node SQLite time-series files (Time Machine). |
| `CHRONOS_PROJECTS_DIR` | Git repositories for flow versioning (Projects). |
| `CHRONOS_FLOW_FILES_DIR` | Sandbox directory the `file in` / `file out` flow nodes are confined to. |

Under the `prod` Spring profile the app **refuses to start** if `CHRONOS_JWT_SECRET` or
`CHRONOS_SECRET_KEY` is left at a blank/dev default.

---

## Ports

| Service | Port | Notes |
|---------|------|-------|
| Frontend (nginx) | **8088** | the web UI |
| Backend (Spring Boot) | 8080 | REST `/api`, WebSocket `/ws`, `/actuator/*` |
| PostgreSQL | 5432 | metadata |
| Mosquitto (MQTT broker) | 1883 | for MQTT flow nodes / adapter |
| OTel Collector | 4318 / 8889 | OTLP HTTP in / Prometheus scrape |
| Jaeger UI | 16686 | traces (observability profile) |

---

## Usage walkthrough

1. **Sign in** at http://localhost:8088 (`admin` / `admin`).
2. **Create a storage node** on the **Tags** page (a retention group), then add tags to it.
3. **Author a flow** on the **Flows** page. A minimal historian pipeline:
   `inject` → `device read` (e.g. a JDBC query) → `Function (JS)` (extract a value) →
   `tag` (write into a historian tag). Click **Deploy & run**, then the ▸ button on the inject node
   fires a message (only fires while the flow is deployed).
4. **Watch it flow** — open the Inspector (bug tab on the right edge) to see live debug messages;
   objects render as a collapsible JSON tree.
5. **See stored data** — the **Dashboard** shows current values; **Time Machine** scrubs a
   point-in-time snapshot and charts a tag's last 24 h.
6. **Consume externally** — a gateway client (Java/Python/.NET SDK) presents `CHRONOS_GATEWAY_TOKEN`,
   subscribes by tag name over the WebSocket, and receives compressed protobuf frames on each update.

RBAC: reads need any role; operational actions (run task, etc.) need **OPERATOR**+; all config
writes need **ADMIN**. The UI disables controls the current role can't use.

---

## Project layout

```
backend/
  core-api/        SPI + value objects (pure JDK)
  core-engine/     collection pipeline · current-value cache · SQLite history
  core-flow/       flow graph + node runtime (framework-free)
  app/             Spring Boot: REST · security · scheduling · flow deploy · persistence
  gateway/         WebSocket + protobuf broadcaster
  adapters/        PF4J plugins: adapter-{jdbc,file,shell,script,mqtt,modbus,tcp} (+ adapter-api)
frontend/          React 19 + Vite + Tailwind v4 + React Flow + CodeMirror + ECharts
proto/             protobuf schema for the gateway wire format
sdk/               sdk-java · sdk-python · sdk-dotnet
docs/              architecture · adding-a-protocol · security
```

---

## Build & test

**Backend** (compiles & runs on JDK 17 via the Gradle toolchain):

```bash
./gradlew build                 # all modules: compile + unit + Testcontainers integration tests
./gradlew :app:test             # just the app module's tests
./gradlew :app:bootJar          # the runnable Spring Boot jar
./gradlew heavyDbTest           # optional: Oracle + MS-SQL E2E (large container images)
```

Integration tests use **Testcontainers**, so a running Docker daemon is required.

**Frontend:**

```bash
cd frontend
pnpm install
pnpm lint && pnpm build && pnpm test
```

**Rebuild & redeploy a single service:**

```bash
docker compose build frontend && docker compose up -d frontend
docker compose build backend  && docker compose up -d backend
```

---

## Security

- **JWT (HS256)** auth issued at `POST /api/auth/login`; role claim drives **RBAC**
  (ADMIN / OPERATOR / VIEWER).
- **Secrets are never stored in plaintext** — device/flow credentials are **AES-GCM** encrypted at
  rest and masked in API responses.
- The **JavaScript Function node** runs in a GraalVM sandbox (`HostAccess.NONE`, no host classes,
  no file/network); Java Function/script code is **trusted-admin** (ADMIN-only to author/deploy).
- `POST /api/flows/in/**` is intentionally public (inbound webhooks for `http in` nodes).
- The `file in` / `file out` nodes are confined to `CHRONOS_FLOW_FILES_DIR` (symlink-escape guarded).

See [`docs/security.md`](docs/security.md) for the full posture.
