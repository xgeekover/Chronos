package io.chronos.app.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Rejects gateway WebSocket handshakes without a valid {@code ?token=} (§9 connect-time auth).
 * Phase 5 uses a single shared token; RBAC-scoped tokens come later.
 */
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private final String expectedToken;

    public TokenHandshakeInterceptor(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        String token = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                    token = pair.substring(eq + 1);
                }
            }
        }
        boolean ok = expectedToken != null && token != null
                && MessageDigest.isEqual(
                        expectedToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8)); // constant-time compare
        if (!ok) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return ok;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
