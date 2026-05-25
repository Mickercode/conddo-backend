package io.conddo;

import io.conddo.api.studio.HttpStudioJobGateway;
import io.conddo.core.studio.StudioJobGateway.StudioJobRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the platform→Studio hand-off (SERVICE_TOPOLOGY.md §4) emits the exact
 * signed intake request and parses the job handle back — without a live Studio —
 * by binding a {@link MockRestServiceServer} to the injected RestClient builder.
 * Also pins the fail-safe: unconfigured Studio is a no-op, never an error.
 */
class StudioJobGatewayTest {

    @Test
    void postsSignedIntakeAndParsesTheJobHandle() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpStudioJobGateway gateway = new HttpStudioJobGateway(builder, "https://studio.test", "svc-token-123");

        UUID tenantId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("https://studio.test/api/jobs/intake"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Studio-Service-Token", "svc-token-123"))
                .andExpect(jsonPath("$.jobTypeId").value("WEBSITE_REVISION"))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.title").value("Website edit — Glam"))
                .andExpect(jsonPath("$.brief.details").value("Bigger logo"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"id\":\"" + jobId
                        + "\",\"jobNumber\":\"WR-9\",\"status\":\"AVAILABLE\"}}", MediaType.APPLICATION_JSON));

        Optional<StudioJobRef> ref = gateway.createJob(
                tenantId, "WEBSITE_REVISION", "Website edit — Glam", Map.of("details", "Bigger logo"));

        server.verify();
        assertThat(ref).isPresent();
        assertThat(ref.get().jobId()).isEqualTo(jobId);
        assertThat(ref.get().jobNumber()).isEqualTo("WR-9");
        assertThat(ref.get().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void unconfiguredGatewayIsANoOp() {
        HttpStudioJobGateway gateway = new HttpStudioJobGateway(RestClient.builder(), "", "");
        assertThat(gateway.createJob(UUID.randomUUID(), "WEBSITE_BUILD", "t", Map.of())).isEmpty();
    }
}
