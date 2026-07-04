package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.service.CollectionService;
import io.chronos.app.service.DeviceService;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import io.chronos.app.service.TaskService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Phase 7 hardening: JWT auth + RBAC on the API, and Prometheus metrics are exposed (§11). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class Phase7HardeningTest {

    @LocalServerPort int port;
    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired TagService tags;
    @Autowired TaskService taskService;
    @Autowired MappingService mappings;
    @Autowired CollectionService collection;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int status(String method, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .method(method, BodyPublishers.noBody());
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), BodyHandlers.discarding()).statusCode();
    }

    private String login(String user, String pass) throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/auth/login")))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(
                                "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                        .build(),
                BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body()).get("token").asText();
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        assertThat(status("GET", "/api/devices", null)).isEqualTo(401);
    }

    @Test
    void adminJwtCanReadAndWrite() throws Exception {
        String token = login("admin", "admin");
        assertThat(status("GET", "/api/devices", token)).isEqualTo(200);
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        String token = login("viewer", "viewer");
        assertThat(status("GET", "/api/devices", token)).isEqualTo(200);
        // a VIEWER may not delete — authorization rejects before reaching the handler (403, not 404)
        assertThat(status("DELETE", "/api/devices/" + UUID.randomUUID(), token)).isEqualTo(403);
    }

    @Test
    void prometheusExposesChronosMetrics() throws Exception {
        // generate a collection metric via the script adapter (no external source)
        DeviceEntity device = devices.create("p7-script", "HOST", "SCRIPT_JAVA", Map.of(), Map.of());
        NodeEntity node = nodes.create("p7node", "phase7", 24);
        TagEntity tag = tags.create(node.getId(), "v", "NUMBER", null, null);
        TaskEntity task = taskService.create(node.getId(), device.getId(), "SCRIPT_JAVA",
                Map.of("script", "return java.util.Map.of(\"v\", 1.0);"), "INTERVAL", 3_600_000L, null, 5_000);
        mappings.create(task.getId(), tag.getId(), Map.of("kind", "COLUMN", "expr", "v"), Map.of());
        collection.runTaskNow(task.getId());

        String body = http.send(
                HttpRequest.newBuilder(URI.create(url("/actuator/prometheus"))).build(),
                BodyHandlers.ofString()).body();
        assertThat(body).contains("chronos_collections");
    }
}
