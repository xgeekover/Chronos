-- Server-side storage for Node-RED-style flow graphs, owned per user (multi-user flows).
-- The in-memory runtime (core-flow / FlowController) still deploys ONE active flow; this table is
-- the durable, per-user flow library that replaces browser localStorage.
--
-- Writes are ADMIN-gated by SecurityConfig's catch-all (POST/PUT/DELETE /api/** = ROLE_ADMIN) —
-- a stored flow may contain a function node (trusted Java), so authoring stays admin-only. Reads
-- are scoped to the owner. id is the client-generated flow id (flow-<uuid>) so PUT is an upsert.
CREATE TABLE IF NOT EXISTS flow_def (
    id         TEXT PRIMARY KEY,
    owner      TEXT NOT NULL REFERENCES app_user(username) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    graph      JSONB NOT NULL DEFAULT '{}',  -- { nodes: [...], edges: [...] }
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_flow_def_owner ON flow_def(owner);
