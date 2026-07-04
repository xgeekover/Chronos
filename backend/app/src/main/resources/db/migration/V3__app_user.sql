-- Application users for JWT auth + RBAC (§11). Roles: ADMIN / OPERATOR / VIEWER.
-- Default users are seeded at startup (BCrypt-hashed) by DefaultUserInitializer when empty.
CREATE TABLE IF NOT EXISTS app_user (
    id            UUID PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
