package io.chronos.gateway;

/**
 * Abstraction over a connected client (a WebSocket session) so the gateway core stays
 * framework-free. The app adapts a Spring {@code WebSocketSession} to this.
 */
public interface ClientSink {
    /** Send one binary protocol frame to the client. */
    void send(byte[] frame);
}
