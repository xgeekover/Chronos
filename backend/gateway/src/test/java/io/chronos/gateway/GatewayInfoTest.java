package io.chronos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewayInfoTest {

    @Test
    void protocolMetadataIsDefined() {
        assertEquals("chronos-ws", GatewayInfo.PROTOCOL);
        assertTrue(GatewayInfo.PROTOCOL_VERSION >= 1);
    }
}
