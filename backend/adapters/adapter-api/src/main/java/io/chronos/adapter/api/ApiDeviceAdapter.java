package io.chronos.adapter.api;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import org.pf4j.Extension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.Map;

/**
 * Calls an HTTP/JSON API (§2 API device) using the JDK {@link HttpClient} (no third-party deps).
 *
 * <p>Device config: optional {@code params.baseUrl}; optional {@code secrets.bearerToken} → an
 * {@code Authorization: Bearer} header. Task definition: {@code url} (required; absolute or
 * resolved against baseUrl), {@code method} (default GET), {@code headers} (map), {@code body}.
 */
@Extension
public class ApiDeviceAdapter implements DeviceAdapter {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public String type() {
        return "API";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.API_CALL);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        String baseUrl = str(config.params().get("baseUrl"));
        if (baseUrl != null) {
            try {
                URI.create(baseUrl);
            } catch (RuntimeException e) {
                throw new AdapterException("invalid baseUrl: " + baseUrl);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String url = str(request.definition().get("url"));
        if (url == null || url.isBlank()) {
            throw new AdapterException("API task definition is missing 'url'");
        }
        String baseUrl = str(config.params().get("baseUrl"));
        URI uri = URI.create(baseUrl == null || url.startsWith("http") ? url : baseUrl + url);
        String method = request.definition().getOrDefault("method", "GET").toString().toUpperCase();
        String body = str(request.definition().get("body"));

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(request.timeout())
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));

        Object headers = request.definition().get("headers");
        if (headers instanceof Map<?, ?> hm) {
            hm.forEach((k, v) -> b.header(String.valueOf(k), String.valueOf(v)));
        }
        String bearer = config.secrets().get("bearerToken");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }

        try {
            HttpResponse<String> resp = client.send(b.build(), BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new AdapterException("HTTP " + resp.statusCode() + " from " + uri);
            }
            return RawResult.ofJson(resp.body(), Instant.now());
        } catch (AdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new AdapterException("API call failed: " + e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
