# Security — Sensitive Areas, Risks & Mitigations

Three areas are security-sensitive by nature (build prompt §0, §11) and are documented here
explicitly. Implementations land in later phases; this is the standing risk register.

## 1. Credentials & secrets (Phase 2+)

- **Risk:** leakage of device connection credentials (DB passwords, API tokens, SSH keys).
- **Mitigations:**
  - Stored as **AES-GCM (256)** ciphertext in `device.connection_config_enc` (BYTEA). Never
    plaintext (invariant rule 4).
  - DEK/KEK envelope encryption; the KEK is externalized (env var / Vault), never in the DB
    or repo.
  - `Secrets.toString()` masks all values; structured logging masks secret keys.
  - `.env` and `*.local` are git-ignored.

## 2. Dynamic code execution — sandboxed JavaScript (flow Function node)

- **Design:** the flow **Function** node runs user JavaScript in a **GraalVM sandbox** with
  `HostAccess.NONE` and host class lookup denied — no access to the JVM, filesystem, or network.
  Each message runs under a **timeout**; a stuck guest is interrupted and its context rebuilt.
- Because this is a genuine sandbox (not a source-level blocklist), it needs no allow/deny list.
  The earlier in-process **Java** code paths — the Java Function option and the `SCRIPT_JAVA`
  device adapter — were **removed** in favour of this single sandboxed language.

## 3. Shell / remote command execution (Phase 4)

- **Risk (high):** command injection, credential theft, lateral movement.
- **Mitigations:**
  - **Command allowlist** — only approved binaries may run.
  - **Argument array separation** — never `sh -c "<interpolated string>"`; arguments are passed
    as a list so shell metacharacters can't inject.
  - Per-command **timeout** + output size cap.
  - Remote = Apache MINA SSHD with key-based auth where possible; host keys verified.

## Cross-cutting

- **RBAC:** ADMIN / OPERATOR / VIEWER (`app_user.role`). Mutating config and running shell
  commands require elevated roles.
- **Transport/auth:** FE→BE JWT; external WS/SDK token auth on connect.
- **Audit:** `audit_log` records actor/action/target for sensitive operations.
- **Network:** metadata DB is separate from any collected source DB.

## Implemented posture (Phase 7) & review

| Area | Implemented | Gap / next |
|------|-------------|-----------|
| REST API auth | **JWT bearer** (HS256) via `POST /api/auth/login`, stateless; **DB-backed users (`app_user`, BCrypt)** with **RBAC** ADMIN/OPERATOR/VIEWER enforced by method+path; default users seeded on first start | rotate the HS256 secret via secret manager; refresh tokens / external IdP (OIDC); per-user audit |
| Gateway auth | connect-time **token** (`chronos.gateway.token`) | per-tenant / RBAC-scoped tokens |
| Secrets | **AES-GCM** at rest, masked in logs, never returned by the API (`@JsonIgnore`) | KEK in Vault/KMS (currently env) |
| Shell adapter | **allowlist + arg-array + timeout + output cap**; SSH host-key = AcceptAll | pin `known_hosts` in production |
| Function node (flows) | user **JavaScript** in a **GraalVM sandbox** (`HostAccess.NONE`, no host classes, no file/network) + per-message **timeout** | true sandbox; no in-process Java compilation remains (Java Function + `SCRIPT_JAVA` adapter removed) |
| Actuator | only `health`/`info`/`prometheus` exposed; rest authenticated | scrape endpoint network-restricted in prod |
| Transport | same-origin via FE/nginx proxy (no CORS surface) | TLS termination at the edge (compose is plain HTTP) |

**Review checklist (run before release):** no plaintext secrets in DB/logs ✓ · API requires auth
✓ (`Phase7HardeningTest`) · gateway rejects bad token ✓ (handshake) · shell allowlist enforced ✓
(`adapter-shell` tests) · JS Function sandbox denies host access ✓ (`JsFunctionTest`) ·
audit logging of sensitive ops ☐ (planned) · dependency CVE scan ☐ (wire to CI).
