-- Persistent flow runtime context (Node-RED persistent context). One row per scope; "global" survives
-- restarts. Flow-scoped context stays in memory (cleared per deploy), so only "global" is stored here.
CREATE TABLE IF NOT EXISTS flow_context (
    scope TEXT PRIMARY KEY,
    data  JSONB NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
