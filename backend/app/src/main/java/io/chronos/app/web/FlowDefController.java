package io.chronos.app.web;

import io.chronos.app.persistence.FlowDefEntity;
import io.chronos.app.persistence.FlowDefRepository;
import io.chronos.app.security.SecretCipher;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-user library of saved flow graphs (multi-user flows). Replaces browser localStorage with
 * server-side, owner-scoped persistence. Reads are scoped to the authenticated user; writes are
 * ADMIN-only via SecurityConfig's {@code POST/PUT/DELETE /api/** = ROLE_ADMIN} catch-all (a stored
 * flow can contain a trusted function node). The id is the client's flow id so PUT is an upsert.
 */
@RestController
@RequestMapping("/api/flow-defs")
public class FlowDefController {

    // node config keys treated as secrets — encrypted at rest, decrypted only on read
    // top-level config keys treated as secrets (encrypted at rest, decrypted on read)
    private static final List<String> SECRET_KEYS =
            List.of("password", "token", "bearerToken", "apiKey", "apikey", "secret");

    private final FlowDefRepository repo;
    private final SecretCipher cipher;

    public FlowDefController(FlowDefRepository repo, SecretCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /** Response shape — the graph plus light metadata (never another user's flows). */
    public record FlowDefDto(String id, String name, Map<String, Object> graph, Instant updatedAt) {}

    private FlowDefDto toDto(FlowDefEntity e) {
        // decrypt secrets for the client (entity is detached here — open-in-view is off)
        transformSecrets(e.getGraph(), cipher::decryptString);
        return new FlowDefDto(e.getId(), e.getName(), e.getGraph(), e.getUpdatedAt());
    }

    /** Walk graph.nodes[].data.config and apply {@code op} to each secret-valued field in place. */
    @SuppressWarnings("unchecked")
    private static void transformSecrets(Map<String, Object> graph, UnaryOperator<String> op) {
        if (graph == null || !(graph.get("nodes") instanceof List<?> nodes)) {
            return;
        }
        for (Object nObj : nodes) {
            if (!(nObj instanceof Map<?, ?> node) || !(node.get("data") instanceof Map<?, ?> data)) {
                continue;
            }
            if (!(data.get("config") instanceof Map<?, ?> cfgRaw)) {
                continue;
            }
            Map<String, Object> cfg = (Map<String, Object>) cfgRaw;
            for (String k : SECRET_KEYS) {
                if (cfg.get(k) instanceof String s && !s.isEmpty()) {
                    cfg.put(k, op.apply(s));
                }
            }
            // device-read nodes carry credentials in a dedicated `secrets` map — encrypt every value
            if (cfg.get("secrets") instanceof Map<?, ?> secretsRaw) {
                Map<String, Object> secrets = (Map<String, Object>) secretsRaw;
                for (var e : secrets.entrySet()) {
                    if (e.getValue() instanceof String s && !s.isEmpty()) {
                        e.setValue(op.apply(s));
                    }
                }
            }
            // http/soap nodes carry credentials inside a headers map (e.g. an Authorization bearer token)
            if (cfg.get("headers") instanceof Map<?, ?> headersRaw) {
                Map<String, Object> headers = (Map<String, Object>) headersRaw;
                for (var e : headers.entrySet()) {
                    if (isSecretHeader(String.valueOf(e.getKey())) && e.getValue() instanceof String s
                            && !s.isEmpty()) {
                        e.setValue(op.apply(s));
                    }
                }
            }
        }
    }

    /** A header name that conventionally holds a credential (case-insensitive). */
    private static boolean isSecretHeader(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.equals("authorization")
                || n.contains("token")
                || n.contains("apikey")
                || n.contains("api-key")
                || n.contains("secret");
    }

    public record SaveFlowDef(String name, Map<String, Object> graph) {}

    @GetMapping
    public List<FlowDefDto> list(@AuthenticationPrincipal Jwt jwt) {
        return repo.findByOwnerOrderByUpdatedAtDesc(jwt.getSubject()).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlowDefDto> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return repo.findByIdAndOwner(id, jwt.getSubject())
                .map(e -> ResponseEntity.ok(toDto(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Create or update a flow owned by the caller. 403 if the id belongs to another user. */
    @PutMapping("/{id}")
    public ResponseEntity<FlowDefDto> save(
            @PathVariable String id,
            @RequestBody SaveFlowDef req,
            @AuthenticationPrincipal Jwt jwt) {
        String owner = jwt.getSubject();
        FlowDefEntity e = repo.findById(id).orElse(null);
        if (e != null && !owner.equals(e.getOwner())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (e == null) {
            e = new FlowDefEntity();
            e.setId(id);
            e.setOwner(owner);
        }
        e.setName(req.name() == null || req.name().isBlank() ? "Flow" : req.name());
        Map<String, Object> graph = req.graph() == null ? new HashMap<>() : req.graph();
        transformSecrets(graph, cipher::encryptString); // encrypt password fields at rest
        e.setGraph(graph);
        return ResponseEntity.ok(toDto(repo.save(e)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return repo.findByIdAndOwner(id, jwt.getSubject())
                .map(e -> {
                    repo.delete(e);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
