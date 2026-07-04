package io.chronos.app.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the gateway WebSocket endpoint at {@code /ws/gateway} with token-checked handshakes (§9). */
@Configuration
@EnableWebSocket
public class GatewayWebSocketConfig implements WebSocketConfigurer {

    private final ChronosWebSocketHandler handler;
    private final String token;

    public GatewayWebSocketConfig(ChronosWebSocketHandler handler,
            @Value("${chronos.gateway.token:chronos-dev-token}") String token) {
        this.handler = handler;
        this.token = token;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/gateway")
                .addInterceptors(new TokenHandshakeInterceptor(token))
                .setAllowedOriginPatterns("*");
    }
}
