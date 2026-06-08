package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.studio.StudioJobGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SOCIAL_AND_CREATIVE_SERVICES_SPEC §6 — brand-package subscriptions.
 * Catalog → subscribe (initial charge) → paid webhook → bundle ride on
 * creative requests → quota exhausted → cancel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BrandPackagesFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";
    private static final String PAYMENTS_SERVICE_TOKEN = "test-payments-token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo")
            .withUsername("conddo_owner")
            .withPassword("owner_password")
            .withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
        registry.add("conddo.security.auth.cookie-secure", () -> "false");
        registry.add("conddo.security.cors.allowed-origins", () -> "https://app.conddo.io");
        registry.add("conddo.signup.seed-sample-data", () -> "false");
        registry.add("payments.service-token", () -> PAYMENTS_SERVICE_TOKEN);
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;
    @MockBean
    private PaymentsGateway paymentsGateway;
    @MockBean
    private StudioJobGateway studioJobGateway;

    @Test
    void offeringsListsSeededCatalog() throws Exception {
        signup("ph-bp-cat", "owner@ph-bp-cat.test");
        String token = login("ph-bp-cat", "owner@ph-bp-cat.test");

        mockMvc.perform(get("/api/v1/brand-packages/offerings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("starter_brand"))
                .andExpect(jsonPath("$.data[0].includes.design_static").value(4));
    }

    /**
     * Bug 1 fix from HANDOFF_2026-06-08.md — {@code GET /usage} must
     * respond cleanly for a tenant without a subscription. The previous
     * 500 spammed BE logs every time the FE Brand Packages page loaded
     * for an unsubscribed tenant.
     */
    @Test
    void usageReturns200WithNullDataForUnsubscribedTenant() throws Exception {
        signup("ph-bp-no-sub", "owner@ph-bp-no-sub.test");
        String token = login("ph-bp-no-sub", "owner@ph-bp-no-sub.test");
        // Stay launcher AND unsubscribed.

        mockMvc.perform(get("/api/v1/brand-packages/usage")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * For an active subscriber, {@code /usage} returns the current
     * period's counts (empty until the first creative request lands)
     * plus the period bounds so the FE can render quota bars.
     */
    @Test
    void usageReturnsEmptyCountsForActiveSubscriberWithNoRequestsYet() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-usage", "https://x", "PENDING")));

        String tenantId = signup("ph-bp-usage", "owner@ph-bp-usage.test");
        String token = login("ph-bp-usage", "owner@ph-bp-usage.test");
        upgradeToGrowth(tenantId);
        String subId = subscribeAndReturnId(token, "starter_brand");
        markSubscriptionActive(subId);

        mockMvc.perform(get("/api/v1/brand-packages/usage")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.counts").exists())
                .andExpect(jsonPath("$.data.periodStart").isNotEmpty())
                .andExpect(jsonPath("$.data.periodEnd").isNotEmpty());
    }

    @Test
    void launcherTenantHitsPlanGateOnSubscribe() throws Exception {
        signup("ph-bp-gate", "owner@ph-bp-gate.test");
        String token = login("ph-bp-gate", "owner@ph-bp-gate.test");
        // Stay on launcher — brand_package_subscription is Growth+.

        mockMvc.perform(post("/api/v1/brand-packages/subscription")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("offeringCode", "starter_brand"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PLAN_UPGRADE_REQUIRED"));
    }

    @Test
    void subscribePersistsPendingPaymentAndReturnsCheckoutUrl() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-1", "https://routepay.test/checkout/RP-bp-1", "PENDING")));

        String tenantId = signup("ph-bp-sub", "owner@ph-bp-sub.test");
        String token = login("ph-bp-sub", "owner@ph-bp-sub.test");
        upgradeToGrowth(tenantId);

        MvcResult result = mockMvc.perform(post("/api/v1/brand-packages/subscription")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("offeringCode", "starter_brand"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.checkoutUrl").value(
                        "https://routepay.test/checkout/RP-bp-1"))
                .andExpect(jsonPath("$.data.subscription.status").value("pending_payment"))
                .andExpect(jsonPath("$.data.offering.code").value("starter_brand"))
                .andReturn();

        String subId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("subscription").path("id").asText();
        assertEquals("RP-bp-1", readSubscriptionPaymentReference(subId));
    }

    @Test
    void paidWebhookFlipsSubscriptionToActiveAndSeedsUsageRow() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-pay-1", "https://x", "PENDING")));

        String tenantId = signup("ph-bp-paid", "owner@ph-bp-paid.test");
        String token = login("ph-bp-paid", "owner@ph-bp-paid.test");
        upgradeToGrowth(tenantId);
        String subId = subscribeAndReturnId(token, "starter_brand");

        // conddo-payments fires the paid webhook with brandPackageSubscriptionId.
        Map<String, Object> notify = Map.of(
                "tenantId", tenantId,
                "paymentId", UUID.randomUUID(),
                "status", "PAID",
                "brandPackageSubscriptionId", subId,
                "paymentReference", "RP-bp-pay-1",
                "amountKobo", 2500000);
        mockMvc.perform(post("/api/v1/internal/payments/notify")
                        .header("X-Payments-Service-Token", PAYMENTS_SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notify)))
                .andExpect(status().isOk());

        assertEquals("active", readSubscriptionStatus(subId));
        // A usage row exists for the current period.
        assertEquals(1, countUsageRows(subId));
    }

    @Test
    void activeSubscriberRidesBundleAndQuotaExhaustionReturns409() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-ride", "https://x", "PENDING")));
        UUID studioJobId = UUID.randomUUID();
        when(studioJobGateway.createJob(any(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(new StudioJobGateway.StudioJobRef(
                        studioJobId, "CD-9001", "QUEUED")));

        String tenantId = signup("ph-bp-ride", "owner@ph-bp-ride.test");
        String token = login("ph-bp-ride", "owner@ph-bp-ride.test");
        upgradeToGrowth(tenantId);
        String subId = subscribeAndReturnId(token, "starter_brand");
        markSubscriptionActive(subId);

        // starter_brand includes design_static: 4 — so 4 free rides, then quota exhausted.
        for (int i = 0; i < 4; i++) {
            MvcResult r = mockMvc.perform(post("/api/v1/creative-services/requests")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "offeringCode", "design_static",
                                    "brief", "Brief " + (i + 1)))))
                    .andExpect(status().isCreated())
                    // Bundle ride: priceKobo=0, no checkoutUrl, status=queued straight away.
                    .andExpect(jsonPath("$.data.request.priceKobo").value(0))
                    .andExpect(jsonPath("$.data.checkoutUrl").doesNotExist())
                    .andExpect(jsonPath("$.data.request.status").value("queued"))
                    .andReturn();
            JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString())
                    .path("data").path("request");
            assertEquals(studioJobId.toString(), body.path("studioJobId").asText());
        }

        // 5th request — quota exhausted, 409 with structured details.
        mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", "design_static",
                                "brief", "Brief 5"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.error.details[0].field").value("offeringCode"));
    }

    @Test
    void offeringNotIncludedInBundleFallsThroughToPaidFlow() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-mix", "https://x", "PENDING")));
        when(paymentsGateway.initCreativeServiceCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-cs-mix", "https://routepay.test/checkout/RP-cs-mix", "PENDING")));

        String tenantId = signup("ph-bp-mix", "owner@ph-bp-mix.test");
        String token = login("ph-bp-mix", "owner@ph-bp-mix.test");
        upgradeToGrowth(tenantId);
        String subId = subscribeAndReturnId(token, "starter_brand");
        markSubscriptionActive(subId);

        // starter_brand does NOT include ad_creative_static — so this falls through
        // to the paid path even though there's an active subscription.
        mockMvc.perform(post("/api/v1/creative-services/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offeringCode", "ad_creative_static",
                                "brief", "Ad creative please"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.checkoutUrl").value(
                        "https://routepay.test/checkout/RP-cs-mix"))
                .andExpect(jsonPath("$.data.request.priceKobo").value(800000));
    }

    @Test
    void cancelFlipsToCancelled() throws Exception {
        when(paymentsGateway.initBrandPackageCharge(any(), any(), any(), any(), any(), any(),
                anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentsGateway.PaymentInitResult(
                        "RP-bp-cancel", "https://x", "PENDING")));

        String tenantId = signup("ph-bp-cancel", "owner@ph-bp-cancel.test");
        String token = login("ph-bp-cancel", "owner@ph-bp-cancel.test");
        upgradeToGrowth(tenantId);
        String subId = subscribeAndReturnId(token, "starter_brand");

        mockMvc.perform(post("/api/v1/brand-packages/subscription/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
    }

    // ----- helpers -----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private String login(String slug, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", slug, "email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private void upgradeToGrowth(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "UPDATE tenant_subscriptions "
                                 + "SET plan_id = (SELECT id FROM subscription_plans WHERE name = 'growth') "
                                 + "WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                if (ps.executeUpdate() >= 1) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial subscription never landed for " + tenantId);
    }

    private String subscribeAndReturnId(String token, String offeringCode) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/brand-packages/subscription")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("offeringCode", offeringCode))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("subscription").path("id").asText();
    }

    /**
     * Flip a subscription straight to {@code active} via SQL — simulates the
     * paid webhook landing without having to thread a full RoutePay flow
     * through every test.
     */
    private void markSubscriptionActive(String subId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE brand_package_subscriptions SET status = 'active' WHERE id = ?::uuid")) {
            ps.setString(1, subId);
            ps.executeUpdate();
        }
    }

    private String readSubscriptionStatus(String subId) throws SQLException {
        return readOne("SELECT status FROM brand_package_subscriptions WHERE id = ?::uuid", subId);
    }

    private String readSubscriptionPaymentReference(String subId) throws SQLException {
        return readOne("SELECT payment_reference FROM brand_package_subscriptions WHERE id = ?::uuid", subId);
    }

    private int countUsageRows(String subId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT count(*) FROM brand_package_usage WHERE subscription_id = ?::uuid")) {
            ps.setString(1, subId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String readOne(String sql, String id) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected one row");
                return rs.getString(1);
            }
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
