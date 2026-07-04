package io.chronos.flow;

import java.util.Map;

/** What an {@code http-response} node sends back to the caller of a matching {@code http-in} node. */
public record HttpReply(int status, Map<String, String> headers, Object body) {

    public HttpReply {
        headers = headers == null ? Map.of() : headers;
    }
}
