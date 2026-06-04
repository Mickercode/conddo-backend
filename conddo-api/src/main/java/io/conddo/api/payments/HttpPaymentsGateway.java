package io.conddo.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.core.payments.PaymentsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed HTTP hand-off to Conddo Payments' internal endpoint (ACTION_LIST §7a).
 * Authenticates with {@code X-Payments-Service-Token}, exactly the same shape
 * as {@link io.conddo.api.studio.HttpStudioJobGateway}.
 *
 * <p>Enabled only when {@code payments.base-url} and
 * {@code payments.service-token} are both set; otherwise it no-ops (returns
 * empty), so signup never fails because the payments service isn't deployed
 * yet. Transport / 5xx failures are caught and logged — the listener calling
 * us records that provisioning is pending, and the manual recovery path
 * (POST to the same internal endpoint with the same payload) flips the row.
 */
@Component
public class HttpPaymentsGateway implements PaymentsGateway {

    static final String SERVICE_TOKEN_HEADER = "X-Payments-Service-Token";
    private static final String PROVISION_PATH = "/api/payments/internal/tenants";
    private static final Logger log = LoggerFactory.getLogger(HttpPaymentsGateway.class);

    /** Match the Studio gateway's timeouts — payments service is on the same Render free tier. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final boolean enabled;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpPaymentsGateway(RestClient.Builder restClientBuilder,
                               @Value("${payments.base-url:}") String baseUrl,
                               @Value("${payments.service-token:}") String serviceToken) {
        this(restClientBuilder, baseUrl, serviceToken, defaultTimeoutFactory());
    }

    /**
     * Test-friendly constructor — pass {@code null} to leave whatever request
     * factory the {@code RestClient.Builder} was bound to (e.g.
     * {@code MockRestServiceServer} in tests) untouched.
     */
    public HttpPaymentsGateway(RestClient.Builder restClientBuilder, String baseUrl, String serviceToken,
                               ClientHttpRequestFactory requestFactoryOverride) {
        this.enabled = !baseUrl.isBlank() && !serviceToken.isBlank();
        if (enabled) {
            RestClient.Builder configured = restClientBuilder
                    .baseUrl(baseUrl.trim())
                    .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken.trim());
            if (requestFactoryOverride != null) {
                configured = configured.requestFactory(requestFactoryOverride);
            }
            this.restClient = configured.build();
        } else {
            this.restClient = null;
        }
    }

    @Override
    public Optional<TenantPaymentsAccount> provisionTenantAccount(UUID tenantId, String tenantSlug,
                                                                  String businessName, String contactEmail) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = Map.of(
                    "tenantId", tenantId,
                    "tenantSlug", tenantSlug,
                    "businessName", businessName,
                    "contactEmail", contactEmail);

            JsonNode response = restClient.post()
                    .uri(PROVISION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode data = response == null ? null : response.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                log.warn("Payments provision returned no data for tenant {}", tenantId);
                return Optional.empty();
            }
            return Optional.of(new TenantPaymentsAccount(
                    tenantId,
                    data.path("routepaySubaccountId").asText(null),
                    data.path("status").asText(null)));
        } catch (RuntimeException ex) {
            log.error("Payments tenant provision failed for {}: {}", tenantId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static ClientHttpRequestFactory defaultTimeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }
}
