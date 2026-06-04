package io.conddo.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.payments.auth.PaymentsServiceTokenFilter;
import io.conddo.payments.routepay.RoutePayClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end on a fully-booted payments service (Testcontainers Postgres,
 * Flyway V1, the in-app security chain). Verifies the three auth surfaces +
 * the happy path:
 * <ol>
 *   <li>Internal: {@code POST /internal/tenants} with {@code X-Payments-Service-Token}
 *       provisions a tenant account.</li>
 *   <li>Tenant: {@code POST /charges} with a Bearer JWT initialises a payment.</li>
 *   <li>Webhook: {@code POST /webhooks/routepay} with a valid HMAC signature
 *       terminalises the payment and triggers the conddo-api notify (mocked).</li>
 *   <li>Webhook with a bad signature is rejected.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentsFlowTest {

    private static final String SERVICE_TOKEN = "test-payments-token";
    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo").withUsername("conddo_owner").withPassword("owner_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payments.service.token", () -> SERVICE_TOKEN);
        registry.add("routepay.webhook-secret", () -> WEBHOOK_SECRET);
        // No JWT public-key file in tests — TestConfig swaps in a stub decoder.
        registry.add("payments.jwt.public-key", () -> "classpath:application.yml");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Test JWT decoder — stamps a {@code tenant_id} claim from whatever the
     * test put in the Bearer header (we just use the raw token as the tenant
     * id). Lets us exercise the tenant-scoped endpoints without RSA key plumbing.
     */
    @TestConfiguration
    static class JwtTestConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", UUID.randomUUID().toString())
                    .claim("tenant_id", token)
                    .claim("tenant_slug", "test-tenant")
                    .claim("role", "TENANT_ADMIN")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(900))
                    .build();
        }
    }

    /** Stub RoutePay client — returns deterministic results so tests are network-free. */
    @TestConfiguration
    static class RoutePayTestConfig {
        @Bean
        @Primary
        RoutePayClient stubRoutePayClient() {
            return new RoutePayClient() {
                @Override
                public SubAccountResult createSubAccount(UUID tenantId, String businessName, String contactEmail) {
                    return new SubAccountResult("RP-SUB-TEST-" + tenantId);
                }

                @Override
                public InitPaymentResult initPayment(InitPaymentRequest request) {
                    return new InitPaymentResult("TXN-" + request.reference(),
                            "https://pay.routepay.test/checkout/" + request.reference());
                }

                @Override
                public GetTransactionResult getTransaction(String routepayReference) {
                    return new GetTransactionResult("TXN-" + routepayReference, "PENDING", null, null);
                }

                @Override
                public boolean isConfigured() {
                    return true;
                }
            };
        }
    }

    @Test
    void fullFlow_provisionTenant_initCharge_webhookTerminalisesPayment() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // 1. Internal provisioning — service token required.
        mockMvc.perform(post("/api/payments/internal/tenants")
                        .header(PaymentsServiceTokenFilter.HEADER, SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId.toString(),
                                "tenantSlug", "amaka",
                                "businessName", "Amaka Styles",
                                "contactEmail", "owner@amaka.test"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.routepaySubaccountId").value("RP-SUB-TEST-" + tenantId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 1a. Wrong service token is rejected.
        mockMvc.perform(post("/api/payments/internal/tenants")
                        .header(PaymentsServiceTokenFilter.HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        // 1b. No service token at all is rejected.
        mockMvc.perform(post("/api/payments/internal/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        // 2. Init a payment via Bearer JWT (the stub decoder reads tenant_id from the token value).
        MvcResult initResult = mockMvc.perform(post("/api/payments/charges")
                        .header("Authorization", "Bearer " + tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderId", orderId.toString(),
                                "customerEmail", "customer@x.test",
                                "customerName", "Test Buyer",
                                "description", "Fashion order",
                                "amountKobo", 50_000L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amountKobo").value(50_000))
                .andExpect(jsonPath("$.data.paymentUrl").exists())
                .andExpect(jsonPath("$.data.reference").exists())
                .andReturn();
        String reference = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .path("data").path("reference").asText();

        // 3. Webhook with a valid HMAC signature terminalises the payment.
        String webhookBody = objectMapper.writeValueAsString(Map.of(
                "reference", reference,
                "status", "PAID",
                "fee", 1250L));
        String signature = "sha256=" + hmac(WEBHOOK_SECRET, webhookBody.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/payments/webhooks/routepay")
                        .header("X-RoutePay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(true))
                .andExpect(jsonPath("$.data.reason").value("PROCESSED"));

        // 3a. Local row reflects PAID via the verify endpoint (which short-circuits on terminal).
        MvcResult getResult = mockMvc.perform(get("/api/payments/charges/" + reference)
                        .header("Authorization", "Bearer " + tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.feeKobo").value(1250))
                .andReturn();

        // 4. Bad signature is rejected — webhook returns 200 (we never error to RoutePay)
        // but reports the result was not processed.
        mockMvc.perform(post("/api/payments/webhooks/routepay")
                        .header("X-RoutePay-Signature", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(false))
                .andExpect(jsonPath("$.data.reason").value("INVALID_SIGNATURE"));

        // 5. Cross-tenant get is a 404 (anti-enumeration).
        mockMvc.perform(get("/api/payments/charges/" + reference)
                        .header("Authorization", "Bearer " + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // 6. Listing the tenant's charges returns one entry.
        mockMvc.perform(get("/api/payments/charges")
                        .header("Authorization", "Bearer " + tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PAID"));
    }

    @Test
    void unauthenticatedTenantEndpointsAre401() throws Exception {
        mockMvc.perform(get("/api/payments/charges"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void initPaymentRejectsBothOrderAndBooking() throws Exception {
        // First provision a tenant so the tenant-account guard doesn't 404 us.
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/api/payments/internal/tenants")
                        .header(PaymentsServiceTokenFilter.HEADER, SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId.toString(),
                                "tenantSlug", "amaka",
                                "businessName", "Amaka Styles",
                                "contactEmail", "owner@amaka.test"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/payments/charges")
                        .header("Authorization", "Bearer " + tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderId", UUID.randomUUID().toString(),
                                "bookingId", UUID.randomUUID().toString(),
                                "customerEmail", "c@x.test",
                                "customerName", "C",
                                "amountKobo", 10_000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    private static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unused")
    private JsonNode parseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    @SuppressWarnings("unused")
    private Optional<String> textOrNull(JsonNode node, String field) {
        return Optional.ofNullable(node.path(field).asText(null));
    }
}
