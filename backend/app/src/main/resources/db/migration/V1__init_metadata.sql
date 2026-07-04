-- Chronos platform metadata schema (Phase 1, data model §D.2).
-- Stores Device/Node/Tag/Task/Mapping/Alarm configuration + audit/collection logs.
-- Time-series samples live in per-node SQLite files, NOT here (§8, ADR-004).

CREATE TABLE device (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    TEXT NOT NULL UNIQUE,
    type                    TEXT NOT NULL CHECK (type IN ('DATABASE','FILE','HOST','API')),
    adapter_type            TEXT NOT NULL,                 -- concrete adapter key, e.g. 'JDBC_MARIADB'
    connection_config_enc   BYTEA,                         -- AES-GCM ciphertext (no plaintext, 불변 규칙 4)
    connection_config_meta  JSONB NOT NULL DEFAULT '{}',   -- non-sensitive metadata only
    status                  TEXT NOT NULL DEFAULT 'UNKNOWN',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE node (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    retention_hours INTEGER NOT NULL CHECK (retention_hours > 0),  -- Time Machine retention (§8)
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tag (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_id       UUID NOT NULL REFERENCES node(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,                            -- unique within a node
    data_type     TEXT NOT NULL CHECK (data_type IN ('NUMBER','STRING','BOOL','JSON')),
    unit          TEXT,
    description   TEXT,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    canonical_key TEXT NOT NULL UNIQUE,                     -- e.g. 'plant1.line2.temperature' (ADR-012)
    UNIQUE (node_id, name)
);

CREATE TABLE task (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    node_id       UUID NOT NULL REFERENCES node(id) ON DELETE CASCADE,
    device_id     UUID NOT NULL REFERENCES device(id) ON DELETE RESTRICT,
    type          TEXT NOT NULL CHECK (type IN ('QUERY','FILE_READ','API_CALL','SHELL','SCRIPT_JAVA')),
    definition    JSONB NOT NULL,                           -- SQL / file pattern / HTTP spec / command / script
    schedule_kind TEXT NOT NULL CHECK (schedule_kind IN ('INTERVAL','CRON')),
    interval_ms   BIGINT CHECK (interval_ms IS NULL OR interval_ms > 0),
    cron_expr     TEXT,
    timeout_ms    INTEGER NOT NULL DEFAULT 30000 CHECK (timeout_ms > 0),
    retry_policy  JSONB NOT NULL DEFAULT '{}',
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- exactly one schedule field is populated, matching schedule_kind
    CHECK ( (schedule_kind = 'INTERVAL' AND interval_ms IS NOT NULL AND cron_expr IS NULL)
         OR (schedule_kind = 'CRON'     AND cron_expr   IS NOT NULL AND interval_ms IS NULL) )
);

CREATE INDEX idx_task_node   ON task(node_id);
CREATE INDEX idx_task_device ON task(device_id);

CREATE TABLE mapping_rule (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id   UUID NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    tag_id    UUID NOT NULL REFERENCES tag(id)  ON DELETE CASCADE,
    extractor JSONB NOT NULL,                              -- {kind:'COLUMN'|'JSONPATH'|'REGEX'|'EXPR', expr:'...'}
    transform JSONB NOT NULL DEFAULT '{}'                  -- {cast, scale, offset, formula}
    -- integrity (tag.node_id = task.node_id) is enforced in the application layer (§D.2)
);

CREATE INDEX idx_mapping_task ON mapping_rule(task_id);
CREATE INDEX idx_mapping_tag  ON mapping_rule(tag_id);

CREATE TABLE alarm_rule (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tag_id    UUID NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    condition JSONB NOT NULL,                              -- threshold / expression
    severity  TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    channels  JSONB NOT NULL DEFAULT '[]',                 -- ['UI','WEBHOOK','EMAIL','NTFY']
    enabled   BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_alarm_tag ON alarm_rule(tag_id);

CREATE TABLE collection_log (
    id           BIGSERIAL PRIMARY KEY,
    task_id      UUID NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    duration_ms  INTEGER,
    status       TEXT NOT NULL,                            -- OK / ERROR / TIMEOUT
    error        TEXT,
    next_fire_at TIMESTAMPTZ,
    tag_count    INTEGER
);

CREATE INDEX idx_collog_task_time ON collection_log(task_id, started_at DESC);

CREATE TABLE audit_log (
    id     BIGSERIAL PRIMARY KEY,
    actor  TEXT,
    action TEXT NOT NULL,
    target TEXT,
    detail JSONB NOT NULL DEFAULT '{}',
    at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('ADMIN','OPERATOR','VIEWER')),  -- RBAC §11
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
