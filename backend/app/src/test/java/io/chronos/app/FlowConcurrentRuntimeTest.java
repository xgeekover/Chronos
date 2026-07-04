package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Phase 2: many flows run concurrently, one runtime per flowId. Deploying/stopping one flow does not
 * affect the others (Node-RED runs every deployed flow at once).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FlowConcurrentRuntimeTest {

    private static final String GRAPH =
            "{\"nodes\":[{\"id\":\"i\",\"type\":\"inject\",\"config\":{\"once\":false,"
            + "\"intervalMs\":300,\"payload\":1}},{\"id\":\"d\",\"type\":\"debug\",\"config\":{}}],"
            + "\"wires\":[{\"source\":\"i\",\"port\":0,\"target\":\"d\"}]}";

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private String url(String p) {
        return "http://localhost:" + port + p;
    }

    private String login() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/auth/login")))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin\"}"))
                        .build(),
                BodyHandlers.ofString());
        return json.readTree(res.body()).get("token").asText();
    }

    private HttpResponse<String> send(String method, String path, String bearer, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private boolean running(String token, String flowId) throws Exception {
        JsonNode s = json.readTree(send("GET", "/api/flows/status?flowId=" + flowId, token, null).body());
        return s.get("running").asBoolean();
    }

    @Test
    void twoFlowsRunConcurrentlyAndStopIndependently() throws Exception {
        String token = login();
        String a = "test-concurrent-a";
        String b = "test-concurrent-b";
        try {
            assertThat(send("POST", "/api/flows/run?flowId=" + a + "&name=A", token, GRAPH).statusCode())
                    .isEqualTo(200);
            assertThat(send("POST", "/api/flows/run?flowId=" + b + "&name=B", token, GRAPH).statusCode())
                    .isEqualTo(200);

            // both flows are running at the same time
            assertThat(running(token, a)).isTrue();
            assertThat(running(token, b)).isTrue();

            // the overview lists at least these two running flows
            JsonNode all = json.readTree(send("GET", "/api/flows/status", token, null).body()).get("flows");
            long mine = 0;
            for (JsonNode f : all) {
                String id = f.get("flowId").asText();
                if (a.equals(id) || b.equals(id)) {
                    mine++;
                }
            }
            assertThat(mine).isEqualTo(2);

            // stopping A leaves B running
            assertThat(send("POST", "/api/flows/stop?flowId=" + a, token, null).statusCode()).isEqualTo(200);
            assertThat(running(token, a)).isFalse();
            assertThat(running(token, b)).isTrue();
        } finally {
            send("POST", "/api/flows/stop?flowId=" + a, token, null);
            send("POST", "/api/flows/stop?flowId=" + b, token, null);
        }
    }
}
