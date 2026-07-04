package io.chronos.gateway;

/**
 * Placeholder for the external gateway module (§9). Phase 5 adds the protobuf schema,
 * the WebSocket endpoint (token auth + LZ4/gzip framing) and subscription management.
 */
public final class GatewayInfo {

    private GatewayInfo() {}

    public static final String PROTOCOL = "chronos-ws";

    /** Protocol version negotiated on connect; bumped when the .proto schema changes. */
    public static final int PROTOCOL_VERSION = 1;
}
