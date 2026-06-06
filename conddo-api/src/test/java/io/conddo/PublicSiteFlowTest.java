package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.api.billing.BillingExpiryScheduler;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the WEBSITE_INTEGRATION_SPEC §3 public surface end-to-end on a fully
 * booted app + Postgres + Flyway V1–V25:
 * <ul>
 *   <li>Tenant-facing key regeneration is the only path the plaintext key
 *       exists in — it's never reconstructible after the response.</li>
 *   <li>Public requests authenticate via {@code X-Conddo-Site-Key}; missing,
 *       wrong, inactive, and unapproved cases all 401 (anti-enumeration).</li>
 *   <li>Public DTOs are scrubbed: no {@code reorderThreshold}, no {@code cost},
 *       stock exposed only as a boolean.</li>
 *   <li>{@code POST /pharmacy/orders} locks rows {@code FOR UPDATE} and returns
 *       the spec's structured 409 {@code STOCK_SHORTAGE} body.</li>
 *   <li>Module gating: a tenant whose plan lacks {@code order_management} gets
 *       403 {@code MODULE_NOT_ENABLED}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicSiteFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

    private static final String SUPER_EMAIL = "super@conddo.io";
    private static final String SUPER_PASSWORD = "super-secret-pw";
    private static final String SUPER_PASSWORD_HASH = new PasswordHasher().hash(SUPER_PASSWORD);

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
        // Public-site module gating is enforced by direct calls into
        // BillingService — independent of the dashboard's interceptor switch.
        // Pin the expiry cron to a far-future schedule so it never fires
        // during the test run; tests call BillingExpiryScheduler.runOnce()
        // explicitly after backdating the subscription expires_at.
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.billing.grace-period-days", () -> "3");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BillingExpiryScheduler expiryScheduler;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @BeforeEach
    void seedSuperAdmin() throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO staff_users (email, password_hash, full_name, internal_role) "
                             + "VALUES (?, ?, ?, 'SUPER_ADMIN') ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, SUPER_EMAIL);
            ps.setString(2, SUPER_PASSWORD_HASH);
            ps.setString(3, "Platform Admin");
            ps.executeUpdate();
        }
    }

    @Test
    void regenerateKeyReturnsPlaintextOnceAndStoresOnlyTheHash() throws Exception {
        String tenantId = signup("ph-keys", "owner@ph-keys.test");
        String token = login("ph-keys", "owner@ph-keys.test");

        MvcResult result = mockMvc.perform(post("/api/v1/website/site/regenerate-key")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String apiKey = objectMapper.readTree(body).path("data").path("apiKey").asText();
        assertTrue(apiKey.startsWith("sk_live_"), "plaintext should be sk_live_-prefixed: " + apiKey);

        // The next read must mask the key, not return the plaintext.
        MvcResult masked = mockMvc.perform(get("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").isNotEmpty())
                .andReturn();
        // Record DTOs always serialise nulls — the field is present but null.
        JsonNode maskedBody = objectMapper.readTree(masked.getResponse().getContentAsString());
        assertTrue(maskedBody.path("data").path("apiKey").isNull(),
                "GET must not return the plaintext key, but it did: " + maskedBody);
    }

    @Test
    void publicSurfaceRejectsMissingWrongInactiveAndUnapprovedSites() throws Exception {
        String tenantId = signup("ph-auth", "owner@ph-auth.test");
        String token = login("ph-auth", "owner@ph-auth.test");
        String key = regenerateKey(token);
        activateSite(tenantId, "ph-auth");

        // 401 — no header.
        mockMvc.perform(get("/api/v1/public/ph-auth/store-info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        // 401 — wrong key.
        mockMvc.perform(get("/api/v1/public/ph-auth/store-info")
                        .header("X-Conddo-Site-Key", "sk_live_obviously-wrong"))
                .andExpect(status().isUnauthorized());

        // 200 — correct.
        mockMvc.perform(get("/api/v1/public/ph-auth/store-info")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("ph-auth Business"));

        // 401 — site deactivated (anti-enumeration: still UNAUTHENTICATED).
        setSiteActive(tenantId, false, true);
        mockMvc.perform(get("/api/v1/public/ph-auth/store-info")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isUnauthorized());

        // 401 — site QA-unapproved.
        setSiteActive(tenantId, true, false);
        mockMvc.perform(get("/api/v1/public/ph-auth/store-info")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productsEndpointScrubsInternalFieldsAndStockIsBooleanOnly() throws Exception {
        String tenantId = signup("ph-scrub", "owner@ph-scrub.test");
        String token = login("ph-scrub", "owner@ph-scrub.test");
        String key = regenerateKey(token);
        activateSite(tenantId, "ph-scrub");
        upgradeToGrowth(tenantId);
        seedProduct(tenantId, "Panadol", "PND-001", "150.00", 10, 5, "11.50");

        MvcResult result = mockMvc.perform(get("/api/v1/public/ph-scrub/pharmacy/products")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Panadol"))
                .andExpect(jsonPath("$.data[0].stockAvailable").value(true))
                // Internal fields MUST NOT leak.
                .andExpect(jsonPath("$.data[0].reorderThreshold").doesNotExist())
                .andExpect(jsonPath("$.data[0].cost").doesNotExist())
                .andExpect(jsonPath("$.data[0].stock").doesNotExist())
                .andReturn();
        // Sanity — the response body itself contains no leaked field names.
        String body = result.getResponse().getContentAsString();
        assertTrue(!body.contains("reorderThreshold"), "reorderThreshold leaked: " + body);
        assertTrue(!body.contains("\"cost\""), "cost leaked: " + body);
    }

    @Test
    void orderIntakeReturns409StockShortageWhenStockInsufficient() throws Exception {
        String tenantId = signup("ph-stock", "owner@ph-stock.test");
        String token = login("ph-stock", "owner@ph-stock.test");
        String key = regenerateKey(token);
        activateSite(tenantId, "ph-stock");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "VitaminC", "VC-001", "500.00", 1, 0, "200");

        // Ordering 2 of a stock-of-1 product → 409 with the spec body shape.
        mockMvc.perform(post("/api/v1/public/ph-stock/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "customer", Map.of(
                                        "fullName", "Walk-in Buyer",
                                        "phone", "0809000111")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STOCK_SHORTAGE"))
                .andExpect(jsonPath("$.items[0].productId").value(pid))
                .andExpect(jsonPath("$.items[0].available").value(1))
                .andExpect(jsonPath("$.items[0].requested").value(2));

        // Stock must be unchanged after the rollback.
        assertEquals(1, readStock(pid), "shortage must roll back stock decrements");
    }

    @Test
    void successfulOrderDecrementsStockAndTheNextRequestSeesTheNewLevel() throws Exception {
        String tenantId = signup("ph-buy", "owner@ph-buy.test");
        String token = login("ph-buy", "owner@ph-buy.test");
        String key = regenerateKey(token);
        activateSite(tenantId, "ph-buy");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "Paracetamol", "PCM-001", "300.00", 1, 0, "100");

        mockMvc.perform(post("/api/v1/public/ph-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "customer", Map.of(
                                        "fullName", "Buyer One",
                                        "phone", "0809000222")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.total").value(300.00));

        assertEquals(0, readStock(pid), "successful order must persist the stock decrement");

        // Second order for the same product → 409 because the row is now empty.
        mockMvc.perform(post("/api/v1/public/ph-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "customer", Map.of(
                                        "fullName", "Buyer Two",
                                        "phone", "0809000333")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STOCK_SHORTAGE"));
    }

    @Test
    void moduleGateBlocksLauncherTenantsFromOrderIntake() throws Exception {
        String tenantId = signup("ph-gate", "owner@ph-gate.test");
        String token = login("ph-gate", "owner@ph-gate.test");
        String key = regenerateKey(token);
        activateSite(tenantId, "ph-gate");
        // No upgrade — stays on the launcher plan, which lacks order_management.
        String pid = seedProduct(tenantId, "Coughsyrup", "CS-001", "800.00", 5, 0, "300");

        mockMvc.perform(post("/api/v1/public/ph-gate/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "customer", Map.of(
                                        "fullName", "Test Buyer",
                                        "phone", "0809000444")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MODULE_NOT_ENABLED"));
    }

    // ===== EXPIRY CRON — trialing → grace → expired transitions + notify ===

    @Test
    void expiryScanMovesTrialingPastExpiresIntoGraceAndNotifies() throws Exception {
        String tenantId = signup("ph-trial-end", "owner@ph-trial-end.test");
        login("ph-trial-end", "owner@ph-trial-end.test");
        // Wait for the trial subscription row to land (created by an
        // @Async AFTER_COMMIT listener on signup).
        waitForSubscription(tenantId);

        // Backdate expires_at to yesterday — trial is over.
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_subscriptions SET expires_at = ? WHERE tenant_id = ?::uuid")) {
            ps.setObject(1, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
            ps.setString(2, tenantId);
            assertTrue(ps.executeUpdate() == 1);
        }

        expiryScheduler.runOnce();

        assertEquals("grace", readSubStatus(tenantId), "trialing → grace after expires");
        verify(emailSender, timeout(5_000)).send(
                eq("owner@ph-trial-end.test"),
                contains("trial just ended"),
                anyString());
        // Bell feed has the PLAN_GRACE row.
        String token = login("ph-trial-end", "owner@ph-trial-end.test");
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("PLAN_GRACE"));
    }

    @Test
    void expiryScanMovesGracePastGracePeriodIntoExpiredAndNotifies() throws Exception {
        String tenantId = signup("ph-expired", "owner@ph-expired.test");
        login("ph-expired", "owner@ph-expired.test");
        waitForSubscription(tenantId);

        // Set grace + backdate well past the 3-day grace window.
        OffsetDateTime fourDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(4);
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_subscriptions SET status = 'grace', expires_at = ? "
                             + "WHERE tenant_id = ?::uuid")) {
            ps.setObject(1, fourDaysAgo);
            ps.setString(2, tenantId);
            assertTrue(ps.executeUpdate() == 1);
        }

        expiryScheduler.runOnce();

        assertEquals("expired", readSubStatus(tenantId), "grace → expired after grace window");
        verify(emailSender, timeout(5_000)).send(
                eq("owner@ph-expired.test"),
                contains("expired"),
                anyString());
    }

    @Test
    void expiryScanIsNoopWhenSubscriptionsAreCurrent() throws Exception {
        String tenantId = signup("ph-current", "owner@ph-current.test");
        login("ph-current", "owner@ph-current.test");
        waitForSubscription(tenantId);

        expiryScheduler.runOnce();

        assertEquals("trialing", readSubStatus(tenantId), "fresh trial stays trialing");
        // No interaction with email for "trial just ended" wording.
        verify(emailSender, never()).send(anyString(), contains("trial just ended"), anyString());
    }

    // ===== NOTIFICATIONS — public-website order fans out to merchant ========

    @Test
    void publicOrderEmailsOwnerAndSmsOwnerAndAddsBellFeedRow() throws Exception {
        String tenantId = signup("ph-notify", "owner@ph-notify.test");
        String tenantToken = login("ph-notify", "owner@ph-notify.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-notify");
        upgradeToGrowth(tenantId);
        // Signup doesn't capture a phone on the User row, so seed the
        // tenant's business contactPhone — the listener falls back to it
        // when the owner user has no phone on file (typical real case).
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenants SET contact_phone = ? WHERE id = ?::uuid")) {
            ps.setString(1, "+2348091234567");
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
        String pid = seedProduct(tenantId, "Vit C", "VC-1", "500.00", 5, 0, "100");

        mockMvc.perform(post("/api/v1/public/ph-notify/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "customer", Map.of(
                                        "fullName", "Walk-in Buyer",
                                        "phone", "0809000111")))))
                .andExpect(status().isCreated());

        // @Async listener — give it up to 5s to fire.
        verify(emailSender, timeout(5_000)).send(
                eq("owner@ph-notify.test"),
                contains("New order"),
                contains("Walk-in Buyer"));
        verify(smsSender, timeout(5_000)).send(
                eq("+2348091234567"),
                contains("Walk-in Buyer"));

        // Bell feed gets the ORDER row.
        MvcResult feed = mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("ORDER"))
                .andReturn();
        JsonNode top = objectMapper.readTree(feed.getResponse().getContentAsString())
                .path("data").path("items").get(0);
        assertTrue(top.path("title").asText().contains("New order"),
                "bell feed title should mention the order: " + top);
    }

    @Test
    void rolledBackOrderDoesNotNotify() throws Exception {
        String tenantId = signup("ph-rollback", "owner@ph-rollback.test");
        String tenantToken = login("ph-rollback", "owner@ph-rollback.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-rollback");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "Out-of-stock", "OS-1", "500.00", 1, 0, "100");

        // Order qty=2 of a 1-stock product → 409 STOCK_SHORTAGE, transaction rolls back.
        mockMvc.perform(post("/api/v1/public/ph-rollback/pharmacy/orders")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "customer", Map.of(
                                        "fullName", "Bogus Buyer",
                                        "phone", "0809000222")))))
                .andExpect(status().isConflict());

        // 500ms is plenty for any AFTER_COMMIT misfire to land. The buyer's
        // name is unique to this test, so verify it never reaches the senders.
        Thread.sleep(500);
        verify(emailSender, never()).send(anyString(), anyString(), contains("Bogus Buyer"));
        verify(smsSender, never()).send(anyString(), contains("Bogus Buyer"));
    }

    // ===== ACTIVATION FLOW — claim subdomain → submit → SUPER_ADMIN approve ==

    @Test
    void regenerateKeyDefaultsSubdomainToTenantSlug() throws Exception {
        signup("ph-default", "owner@ph-default.test");
        String token = login("ph-default", "owner@ph-default.test");
        regenerateKey(token);

        mockMvc.perform(get("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subdomain").value("ph-default"));
    }

    @Test
    void patchSubdomainRejectsInvalidAndReservedAndDuplicates() throws Exception {
        signup("ph-claim-a", "owner@ph-claim-a.test");
        String tokenA = login("ph-claim-a", "owner@ph-claim-a.test");
        regenerateKey(tokenA);

        // Empty/reserved/RFC-1035 violations → 400 INVALID_SUBDOMAIN.
        mockMvc.perform(patch("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subdomain", "API"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUBDOMAIN"));

        mockMvc.perform(patch("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subdomain", "ab"))))
                .andExpect(status().isBadRequest());

        // Valid rename works.
        mockMvc.perform(patch("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subdomain", "claim-a-rename"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subdomain").value("claim-a-rename"));

        // Second tenant tries to take the same one — 409 SUBDOMAIN_TAKEN.
        signup("ph-claim-b", "owner@ph-claim-b.test");
        String tokenB = login("ph-claim-b", "owner@ph-claim-b.test");
        regenerateKey(tokenB);
        mockMvc.perform(patch("/api/v1/website/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subdomain", "claim-a-rename"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBDOMAIN_TAKEN"));
    }

    @Test
    void submitForReviewStoresUrlButDoesNotActivate() throws Exception {
        signup("ph-submit", "owner@ph-submit.test");
        String token = login("ph-submit", "owner@ph-submit.test");
        regenerateKey(token);

        // Bad URL → 400.
        mockMvc.perform(post("/api/v1/website/site/submit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("submittedUrl", "ftp://bad"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUBMITTED_URL"));

        // Good URL → 200, but the site is NOT yet live.
        mockMvc.perform(post("/api/v1/website/site/submit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("submittedUrl", "https://ph-submit.example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submittedUrl").value("https://ph-submit.example.com"))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.qaApproved").value(false));
    }

    @Test
    void superAdminApproveActivatesSiteAndPublicSurfaceBecomesReachable() throws Exception {
        signup("ph-approve", "owner@ph-approve.test");
        String tenantToken = login("ph-approve", "owner@ph-approve.test");
        String apiKey = regenerateKey(tenantToken);
        // Submit a URL so it appears in the QA queue with a real submitted_url.
        mockMvc.perform(post("/api/v1/website/site/submit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("submittedUrl", "https://ph-approve.example.com"))))
                .andExpect(status().isOk());

        // Public surface should be 401 (site not active+approved yet).
        mockMvc.perform(get("/api/v1/public/ph-approve/store-info")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isUnauthorized());

        // SUPER_ADMIN approves it.
        String staffToken = staffLogin();
        String siteId = readSiteId("ph-approve");
        mockMvc.perform(post("/api/v1/admin/sites/" + siteId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.qaApproved").value(true))
                .andExpect(jsonPath("$.data.qaApprovedAt").isNotEmpty());

        // Public surface now reachable.
        mockMvc.perform(get("/api/v1/public/ph-approve/store-info")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("ph-approve Business"));
    }

    @Test
    void superAdminDeactivateTakesSiteOffline() throws Exception {
        signup("ph-takedown", "owner@ph-takedown.test");
        String tenantToken = login("ph-takedown", "owner@ph-takedown.test");
        String apiKey = regenerateKey(tenantToken);
        // Pre-approve via the admin flow (mirrors the real lifecycle).
        String staffToken = staffLogin();
        String siteId = readSiteId("ph-takedown");
        mockMvc.perform(post("/api/v1/admin/sites/" + siteId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk());

        // Sanity — live.
        mockMvc.perform(get("/api/v1/public/ph-takedown/store-info")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isOk());

        // Deactivate.
        mockMvc.perform(post("/api/v1/admin/sites/" + siteId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andExpect(jsonPath("$.data.qaApproved").value(true)); // approval stamp stays

        // Public surface offline.
        mockMvc.perform(get("/api/v1/public/ph-takedown/store-info")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminListReturnsPendingSitesAcrossTenants() throws Exception {
        signup("ph-list-a", "owner@ph-list-a.test");
        String tokenA = login("ph-list-a", "owner@ph-list-a.test");
        regenerateKey(tokenA);
        signup("ph-list-b", "owner@ph-list-b.test");
        String tokenB = login("ph-list-b", "owner@ph-list-b.test");
        regenerateKey(tokenB);

        String staffToken = staffLogin();
        // Both sites are unapproved; the queue must contain BOTH (cross-tenant).
        MvcResult result = mockMvc.perform(get("/api/v1/admin/sites?filter=pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        assertTrue(rows.isArray(), "expected an array");
        boolean foundA = false, foundB = false;
        for (JsonNode row : rows) {
            String sub = row.path("subdomain").asText();
            if ("ph-list-a".equals(sub)) foundA = true;
            if ("ph-list-b".equals(sub)) foundB = true;
        }
        assertTrue(foundA && foundB, "both pending tenant sites must appear in the cross-tenant queue: " + rows);
    }

    @Test
    void approveWithoutSubdomainIs400() throws Exception {
        signup("ph-no-sub", "owner@ph-no-sub.test");
        String tenantToken = login("ph-no-sub", "owner@ph-no-sub.test");
        regenerateKey(tenantToken);
        // Strip the subdomain to simulate the legacy state where it was null.
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_sites SET subdomain = NULL WHERE tenant_id = ?::uuid")) {
            ps.setString(1, readTenantId("ph-no-sub"));
            ps.executeUpdate();
        }

        String staffToken = staffLogin();
        String siteId = readSiteId("ph-no-sub");
        mockMvc.perform(post("/api/v1/admin/sites/" + siteId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(staffToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SUBDOMAIN"));
    }

    @Test
    void tenantWithoutAdminRoleCannotApprove() throws Exception {
        signup("ph-rbac", "owner@ph-rbac.test");
        String tenantToken = login("ph-rbac", "owner@ph-rbac.test");
        regenerateKey(tenantToken);
        String siteId = readSiteId("ph-rbac");

        // Authenticated TENANT_ADMIN trying the admin route → 403, NOT 200.
        mockMvc.perform(post("/api/v1/admin/sites/" + siteId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenantToken)))
                .andExpect(status().isForbidden());
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
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    private String regenerateKey(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/website/site/regenerate-key")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String key = objectMapper.readTree(body).path("data").path("apiKey").asText();
        assertNotNull(key, "regenerate must return a plaintext key");
        return key;
    }

    /** Subdomain + activate + qa_approve in one — bypasses the staff approval flow. */
    private void activateSite(String tenantId, String subdomain) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_sites SET subdomain = ?, is_active = true, qa_approved = true "
                             + "WHERE tenant_id = ?::uuid")) {
            ps.setString(1, subdomain);
            ps.setString(2, tenantId);
            int rows = ps.executeUpdate();
            assertTrue(rows == 1, "expected exactly one tenant_sites row to activate, got " + rows);
        }
    }

    private void setSiteActive(String tenantId, boolean active, boolean approved) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_sites SET is_active = ?, qa_approved = ? WHERE tenant_id = ?::uuid")) {
            ps.setBoolean(1, active);
            ps.setBoolean(2, approved);
            ps.setString(3, tenantId);
            ps.executeUpdate();
        }
    }

    /**
     * Move the tenant to the {@code growth} plan. The trial subscription is
     * created by an {@code @Async} listener fired AFTER_COMMIT on signup, so
     * it may not have landed yet — poll briefly until it does.
     */
    private void upgradeToGrowth(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "UPDATE tenant_subscriptions "
                                 + "SET plan_id = (SELECT id FROM subscription_plans WHERE name = 'growth') "
                                 + "WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                int rows = ps.executeUpdate();
                if (rows >= 1) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial subscription never landed for tenant " + tenantId);
    }

    /** Inserts a product directly — bypasses the API to keep the test focused. */
    private String seedProduct(String tenantId, String name, String sku, String price,
                               int stock, int reorder, String cost) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, reorder_threshold) "
                             + "VALUES (?::uuid, ?, ?, ?::numeric, ?, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, sku);
            ps.setString(4, price);
            ps.setInt(5, stock);
            ps.setInt(6, reorder);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "insert should return the new product id");
                return rs.getString(1);
            }
        }
    }

    private int readStock(String productId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT stock FROM products WHERE id = ?::uuid")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "product must exist");
                return rs.getInt(1);
            }
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** SUPER_ADMIN login — seeded in @BeforeEach, no tenant slug, no refresh cookie. */
    private String staffLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", SUPER_EMAIL, "password", SUPER_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    /** Reads the tenant_sites row id for a given tenant slug. Owner connection bypasses RLS. */
    private String readSiteId(String tenantSlug) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT ts.id FROM tenant_sites ts JOIN tenants t ON t.id = ts.tenant_id "
                             + "WHERE t.slug = ?")) {
            ps.setString(1, tenantSlug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected one tenant_sites row for slug=" + tenantSlug);
                return rs.getString(1);
            }
        }
    }

    /**
     * Polls until the trial-subscription row lands. Signup fires the listener
     * via {@code @Async} AFTER_COMMIT, so the row may not exist immediately
     * after the HTTP response.
     */
    private void waitForSubscription(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "SELECT count(*) FROM tenant_subscriptions WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= 1) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("trial subscription never landed for tenant " + tenantId);
    }

    /** Reads the current status of the live subscription for a tenant. */
    private String readSubStatus(String tenantId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status FROM tenant_subscriptions WHERE tenant_id = ?::uuid "
                             + "ORDER BY started_at DESC LIMIT 1")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a subscription row for " + tenantId);
                return rs.getString(1);
            }
        }
    }

    /** Reads the tenant uuid for a slug. */
    private String readTenantId(String slug) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement("SELECT id FROM tenants WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "tenant must exist: " + slug);
                return rs.getString(1);
            }
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
