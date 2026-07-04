-- Audit trail for the flow runtime: who deployed/stopped which flow and when, plus deploy-time errors.
-- The runtime is a single shared instance, so this is the history of what was made live.
CREATE TABLE IF NOT EXISTS flow_audit (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor      TEXT NOT NULL,                 -- app_user.username that performed the action
    action     TEXT NOT NULL CHECK (action IN ('DEPLOY', 'STOP', 'ERROR')),
    flow_name  TEXT,                          -- human label of the flow (best-effort)
    node_count INT,
    detail     TEXT
);

CREATE INDEX IF NOT EXISTS idx_flow_audit_at ON flow_audit(at DESC);
