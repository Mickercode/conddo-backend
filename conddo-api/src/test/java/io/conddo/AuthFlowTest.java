package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.api.security.JwtTenantContextFilter;
import io.conddo.api.security.RefreshCookies;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.storage.ObjectStorage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the Phase 1 auth spine on a real, fully-booted app
 * (Testcontainers Postgres, Flyway V1–V5, the two-role model). Booting also
 * validates that every JPA entity matches the migrated schema (ddl-auto:
 * validate). Covers: tenant signup, login, JWT-driven RLS isolation (no
 * X-Tenant-Id header), refresh-token rotation + reuse detection, logout,
 * password reset, SUPER_ADMIN act-as, and method-level authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

    private static final String SUPER_EMAIL = "super@conddo.io";
    private static final String SUPER_PASSWORD = "super-secret-pw";
    /** A valid BCrypt hash of SUPER_PASSWORD, computed once (BCrypt is slow). */
    private static final String SUPER_PASSWORD_HASH = new PasswordHasher().hash(SUPER_PASSWORD);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo")
            .withUsername("conddo_owner")
            .withPassword("owner_password")
            .withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // App connects as the non-owner role (RLS applies); Flyway as the owner.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
        registry.add("conddo.security.auth.cookie-secure", () -> "false");
        registry.add("conddo.security.cors.allowed-origins", () -> "https://app.conddo.io");
        // Don't seed sample data in tests — existing assertions rely on
        // a tenant having zero customers / products immediately after signup.
        registry.add("conddo.signup.seed-sample-data", () -> "false");
        // Don't enforce billing feature gates in the e2e harness — every
        // existing test creates Launcher-tier tenants (planId=starter
        // → launcher) and exercises modules now gated to Growth+.
        // Per-feature gate tests live in dedicated unit tests.
        registry.add("conddo.billing.enforce-feature-gates", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    /**
     * In-memory object storage so the media endpoints can be exercised end-to-end
     * without a live Cloudinary. {@code @Primary} overrides the real adapter.
     */
    @TestConfiguration
    static class StorageTestConfig {
        @Bean
        @Primary
        ObjectStorage inMemoryObjectStorage() {
            return new ObjectStorage() {
                private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public Stored put(String key, String contentType, long size, java.io.InputStream data) {
                    try {
                        store.put(key, data.readAllBytes());
                    } catch (java.io.IOException e) {
                        throw new io.conddo.core.storage.StorageException("read failed", e);
                    }
                    return new Stored(key, "http://test-storage/" + key);
                }

                @Override
                public void delete(String id) {
                    store.remove(id);
                }
            };
        }
    }

    /**
     * Stub Google ID-token verifier (§1a). The default returns empty so every
     * Google endpoint replies 400 GOOGLE_ID_TOKEN_INVALID; a specific test can
     * call {@link GoogleVerifierTestConfig#stub} to make the next verify call
     * return a specific identity.
     */
    @TestConfiguration
    static class GoogleVerifierTestConfig {
        static volatile io.conddo.core.auth.GoogleIdentity stubbed;

        static void stub(io.conddo.core.auth.GoogleIdentity identity) {
            stubbed = identity;
        }

        @Bean
        @Primary
        io.conddo.core.auth.GoogleIdTokenVerifier stubGoogleVerifier() {
            return new io.conddo.core.auth.GoogleIdTokenVerifier() {
                @Override
                public java.util.Optional<io.conddo.core.auth.GoogleIdentity> verify(String idToken) {
                    return java.util.Optional.ofNullable(stubbed);
                }

                @Override
                public boolean isConfigured() {
                    return true;
                }
            };
        }
    }

    /**
     * Recording stub for the Studio job-intake gateway. Returns empty (so existing
     * website-change-request tests still see {@code PENDING}); exposes the last
     * call's args so the tenant-activated auto-create flow can be asserted.
     */
    @TestConfiguration
    static class StudioGatewayTestConfig {
        static volatile java.util.UUID lastTenantId;
        static volatile String lastJobType;
        static volatile String lastTitle;
        static volatile java.util.Map<String, Object> lastBrief;

        @Bean
        @Primary
        io.conddo.core.studio.StudioJobGateway recordingStudioJobGateway() {
            return (tenantId, jobType, title, brief) -> {
                lastTenantId = tenantId;
                lastJobType = jobType;
                lastTitle = title;
                lastBrief = brief;
                return java.util.Optional.empty();
            };
        }
    }

    /**
     * Seeds a SUPER_ADMIN into the internal {@code staff_users} table (no tenant),
     * as the owner role. Runs per test but is idempotent — there is intentionally
     * no API to create platform staff.
     */
    @BeforeEach
    void seedSuperAdmin() throws SQLException {
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
    void loginIssuesTokenThatScopesTenantAccess() throws Exception {
        signup("flow-a", "owner@flow-a.test");
        String token = login("flow-a", "owner@flow-a.test", PASSWORD);

        // Authenticated, no X-Tenant-Id header — the tenant comes from the JWT.
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", "Alice"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alice"));
    }

    @Test
    void tenantsSeeOnlyTheirOwnDataViaJwtClaim() throws Exception {
        signup("iso-a", "a@iso.test");
        signup("iso-b", "b@iso.test");
        String tokenA = login("iso-a", "a@iso.test", PASSWORD);
        String tokenB = login("iso-b", "b@iso.test", PASSWORD);

        createCustomer(tokenA, "Alice-A");
        createCustomer(tokenB, "Bob-B");

        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alice-A"));
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Bob-B"));
    }

    @Test
    void refreshRotatesTokenAndReuseOfOldTokenKillsTheFamily() throws Exception {
        signup("rt-a", "owner@rt.test");
        String cookie1 = refreshCookieValue(performLogin("rt-a", "owner@rt.test", PASSWORD));

        MvcResult refreshed = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie(RefreshCookies.COOKIE_NAME, cookie1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String cookie2 = refreshCookieValue(refreshed);
        assertNotEquals(cookie1, cookie2, "refresh must rotate the token");

        mockMvc.perform(post("/auth/refresh").cookie(new Cookie(RefreshCookies.COOKIE_NAME, cookie1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));

        // Reuse burned the whole family, so even the rotated token is now dead.
        mockMvc.perform(post("/auth/refresh").cookie(new Cookie(RefreshCookies.COOKIE_NAME, cookie2)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshTokenAndClearsTheCookie() throws Exception {
        signup("lo-a", "owner@lo.test");
        String cookie = refreshCookieValue(performLogin("lo-a", "owner@lo.test", PASSWORD));

        MvcResult logout = mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(RefreshCookies.COOKIE_NAME, cookie)))
                .andExpect(status().isOk())
                .andReturn();
        String cleared = logout.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cleared);
        assertTrue(cleared.contains("Max-Age=0"), "logout must expire the cookie");

        mockMvc.perform(post("/auth/refresh").cookie(new Cookie(RefreshCookies.COOKIE_NAME, cookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetReplacesPasswordAndRejectsTheOldOne() throws Exception {
        signup("pr-a", "owner@pr.test");

        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "pr-a", "email", "owner@pr.test"))))
                .andExpect(status().isOk());

        // The reset email carries the token; it's sent via the branded HTML template
        // (sendHtml), so capture the text fallback (arg 4) and pull the token out.
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendHtml(eq("owner@pr.test"), anyString(), anyString(), bodyCaptor.capture());
        String resetToken = extractResetToken(bodyCaptor.getValue());

        mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", resetToken, "newPassword", "brand-new-pass"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "pr-a", "email", "owner@pr.test", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "pr-a", "email", "owner@pr.test", "password", "brand-new-pass"))))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminActsOnTenantViaHeaderButOthersCannot() throws Exception {
        String tenantId = signupReturningId("sa-a", "admin@sa.test");
        String adminToken = login("sa-a", "admin@sa.test", PASSWORD);
        createCustomer(adminToken, "Alice-SA");

        String superToken = staffLogin(SUPER_EMAIL, SUPER_PASSWORD);

        // SUPER_ADMIN scopes to the chosen tenant via the header -> sees its data.
        mockMvc.perform(get("/api/v1/customers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superToken))
                        .header(JwtTenantContextFilter.ACT_AS_TENANT_HEADER, tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alice-SA"));

        // Staff carry no tenant; without the header there is no tenant selected,
        // so a tenant-scoped call fails closed (must act-as).
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(superToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_TENANT"));

        // A non-super-admin's act-as header is ignored -> still only their own tenant.
        mockMvc.perform(get("/api/v1/customers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .header(JwtTenantContextFilter.ACT_AS_TENANT_HEADER, "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alice-SA"));
    }

    @Test
    void listingAllTenantsRequiresSuperAdmin() throws Exception {
        signup("list-a", "admin@list.test");
        String adminToken = login("list-a", "admin@list.test", PASSWORD);
        String superToken = staffLogin(SUPER_EMAIL, SUPER_PASSWORD);

        mockMvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, bearer(superToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void stagedSignupVerifiesPhoneThenCreatesTenantAndLogsIn() throws Exception {
        String phone = "+2348030000001";
        String email = "amaka@biz.test";
        String regId = registerStart("Amaka", phone, email);

        // Verify with the code the (stubbed) OTP email "sent".
        mockMvc.perform(post("/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationId", regId, "code", capturedOtp(email)))))
                .andExpect(status().isOk());

        // Complete -> tenant + admin created, logged in. Slug auto-derived from name.
        MvcResult complete = mockMvc.perform(post("/auth/register/complete").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationId", regId, "businessName", "Amaka Styles",
                                "businessType", "fashion", "planId", "starter"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("TENANT_ADMIN"))
                .andReturn();
        String token = objectMapper.readTree(complete.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        // The new account works against its own (empty) tenant-scoped CRM,
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // and can log in normally using the auto-generated slug "amaka-styles".
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "amaka-styles", "email", "amaka@biz.test", "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void wrongOtpIsRejected() throws Exception {
        String email = "bola@biz.test";
        String regId = registerStart("Bola", "+2348030000002", email);
        String wrong = capturedOtp(email).equals("0000") ? "1111" : "0000";

        mockMvc.perform(post("/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationId", regId, "code", wrong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_OTP"));
    }

    @Test
    void completingSignupBeforeVerifyingIsRejected() throws Exception {
        String regId = registerStart("Chidi", "+2348030000003", "chidi@biz.test");

        mockMvc.perform(post("/auth/register/complete").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationId", regId, "businessName", "Chidi Foods",
                                "businessType", "food", "planId", "free"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_PHONE_NOT_VERIFIED"));
    }

    @Test
    void repeatedFailedLoginsLockTheAccount() throws Exception {
        signup("lock-a", "owner@lock.test");
        // 5 wrong-password attempts — each rejected, and (crucially) each must
        // PERSIST the incremented counter across requests.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("tenantSlug", "lock-a", "email", "owner@lock.test", "password", "WRONGpw" + i))))
                    .andExpect(status().isUnauthorized());
        }
        // The account is now locked: even the correct password is refused.
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "lock-a", "email", "owner@lock.test", "password", PASSWORD))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_LOCKED"));
    }

    @Test
    void sensitiveActionsAreWrittenToAuditLog() throws Exception {
        String tenantId = signupReturningId("audit-a", "owner@audit.test");
        String token = login("audit-a", "owner@audit.test", PASSWORD);   // -> LOGIN audit row
        createCustomer(token, "Audited Alice");                          // -> CUSTOMER_CREATED audit row

        assertTrue(countAudit(tenantId, "LOGIN") >= 1, "expected a LOGIN audit row");
        assertTrue(countAudit(tenantId, "CUSTOMER_CREATED") >= 1, "expected a CUSTOMER_CREATED audit row");
        // A failed login is audited too (persists despite the rejection).
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "audit-a", "email", "owner@audit.test", "password", "nope"))))
                .andExpect(status().isUnauthorized());
        assertTrue(countAudit(tenantId, "LOGIN_FAILED") >= 1, "expected a LOGIN_FAILED audit row");
    }

    @Test
    void meAndVerticalConfigPowerTheDashboardShell() throws Exception {
        signup("dash-a", "owner@dash.test");
        String token = login("dash-a", "owner@dash.test", PASSWORD);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.slug").value("dash-a"))
                .andExpect(jsonPath("$.data.tenant.subdomain").value("dash-a"))
                .andExpect(jsonPath("$.data.user.email").value("owner@dash.test"))
                .andExpect(jsonPath("$.data.user.role").value("TENANT_ADMIN"));

        mockMvc.perform(get("/api/v1/verticals/fashion/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStages[0]").value("Received"))
                .andExpect(jsonPath("$.data.measurementFields[0].key").value("chest"));

        mockMvc.perform(get("/api/v1/verticals/nope/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // /me requires authentication.
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightAllowsConfiguredOriginWithCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/customers")
                        .header("Origin", "https://app.conddo.io")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.conddo.io"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void unauthenticatedAccessIsRejectedWithEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongPasswordIsRejectedAsInvalidCredentials() throws Exception {
        signup("wp-a", "owner@wp.test");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", "wp-a", "email", "owner@wp.test", "password", "WRONG-pass"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void customerProfileSupportsContactNotesMeasurementsAndTags() throws Exception {
        signup("crm-a", "owner@crm.test");
        String token = login("crm-a", "owner@crm.test", PASSWORD);
        String id = createCustomerReturningId(token, "Amaka Styles");

        // Profile reads back with the list-shape `name` and 0 orders (Orders §11.4 not built).
        mockMvc.perform(get("/api/v1/customers/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Amaka Styles"))
                .andExpect(jsonPath("$.data.orders").value(0));

        // PATCH only the sent contact field.
        mockMvc.perform(patch("/api/v1/customers/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", "+2348030000099"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("+2348030000099"));

        // Notes round-trip.
        mockMvc.perform(put("/api/v1/customers/" + id + "/notes").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notes", "Prefers Ankara"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/" + id + "/notes").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes").value("Prefers Ankara"));

        // Measurements round-trip (vertical-specific keys, free-form values).
        mockMvc.perform(put("/api/v1/customers/" + id + "/measurements").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("measurements", Map.of("chest", 40, "waist", 32)))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/" + id + "/measurements").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measurements.chest").value(40));

        // Tag add -> primary tag; remove -> empty.
        mockMvc.perform(post("/api/v1/customers/" + id + "/tags").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tag", "VIP"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tag").value("VIP"))
                .andExpect(jsonPath("$.data.tags[0]").value("VIP"));
        mockMvc.perform(delete("/api/v1/customers/" + id + "/tags").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("tag", "VIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags.length()").value(0));
    }

    @Test
    void listSupportsSearchAndSegmentsAndDeleteRemovesACustomer() throws Exception {
        signup("crm-b", "owner@crmb.test");
        String token = login("crm-b", "owner@crmb.test", PASSWORD);
        createCustomer(token, "Ngozi Tailor");
        String bId = createCustomerReturningId(token, "Bola Fabrics");

        // Search narrows by name (case-insensitive) and reports total in meta.
        mockMvc.perform(get("/api/v1/customers").param("search", "ngozi")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Ngozi Tailor"))
                .andExpect(jsonPath("$.meta.total").value(1));

        // Segments lead with "all" carrying the full count (2).
        mockMvc.perform(get("/api/v1/customers/segments").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("all"))
                .andExpect(jsonPath("$.data[0].count").value(2));

        // Delete -> 204, then the profile is gone.
        mockMvc.perform(delete("/api/v1/customers/" + bId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/customers/" + bId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void orderLifecycleCreateTransitionPayAndLog() throws Exception {
        String token = signupVerticalAndLogin("ord-a", "owner@ord.test", "fashion");
        String customerId = createCustomerReturningId(token, "Chidi Benson");

        // Create an order linked to the customer, with one line item (amount derived).
        String createBody = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId,
                "service", "Ankara Two-Piece",
                "dueDate", "2025-11-14",
                "items", List.of(Map.of("description", "Ankara Two-Piece", "quantity", 1, "unitPrice", 45000))));
        MvcResult created = mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.stage").value("Received"))     // fashion's first stage
                .andExpect(jsonPath("$.data.customer.name").value("Chidi Benson"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        JsonNode createdData = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        assertTrue(createdData.path("reference").asText().startsWith("ORD-"), "reference label");
        assertEquals(0, createdData.path("amount").decimalValue().compareTo(new BigDecimal("45000")), "amount from items");
        assertEquals(0, createdData.path("billing").path("balance").decimalValue().compareTo(new BigDecimal("45000")), "unpaid balance");
        String orderId = createdData.path("id").asText();

        // It appears on the Kanban board under "Received".
        mockMvc.perform(get("/api/v1/orders/board").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stages[0].name").value("Received"))
                .andExpect(jsonPath("$.data.stages[0].count").value(1))
                .andExpect(jsonPath("$.data.stages[0].orders[0].customer").value("Chidi Benson"));

        // Move it forward a stage.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/transition").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("stage", "Measurement Taken"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("Measurement Taken"));

        // Record a deposit -> balance drops by that amount.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 22500, "method", "Bank Transfer"))))
                .andExpect(status().isCreated());
        MvcResult afterPay = mockMvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode billing = objectMapper.readTree(afterPay.getResponse().getContentAsString()).path("data").path("billing");
        assertEquals(0, billing.path("deposit").decimalValue().compareTo(new BigDecimal("22500")), "deposit paid");
        assertEquals(0, billing.path("balance").decimalValue().compareTo(new BigDecimal("22500")), "remaining balance");

        // The activity log captured create + transition + payment.
        mockMvc.perform(get("/api/v1/orders/" + orderId + "/activity").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        // Transition to a stage outside the pipeline is rejected.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/transition").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("stage", "Nonexistent"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void orderStagesFallBackToVerticalDefaultsThenCustomize() throws Exception {
        String token = signupVerticalAndLogin("ord-s", "owner@ords.test", "fashion");

        // No overrides yet -> the fashion vertical's six default stages, "Received" first.
        mockMvc.perform(get("/api/v1/orders/stages").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].name").value("Received"));

        // The first edit materialises the defaults and appends the new stage.
        mockMvc.perform(post("/api/v1/orders/stages").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Quality Check"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("Quality Check"));

        // Now they are stored rows (with ids), seven in total.
        mockMvc.perform(get("/api/v1/orders/stages").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[0].id").isNotEmpty());
    }

    @Test
    void dashboardSummaryAndSetupChecklistReflectTenantState() throws Exception {
        String token = signupVerticalAndLogin("dash-kpi", "owner@dashkpi.test", "fashion");

        // Fresh tenant: business profile + vertical are done, the rest pending -> 2 of 6.
        mockMvc.perform(get("/api/v1/dashboard/setup-checklist").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.completed").value(2))
                .andExpect(jsonPath("$.data.steps[2].key").value("add_customer"))
                .andExpect(jsonPath("$.data.steps[2].done").value(false));

        // Add a customer, an order, and a payment.
        String customerId = createCustomerReturningId(token, "Chidi Benson");
        String createBody = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId, "service", "Senator Suit",
                "items", List.of(Map.of("description", "Senator Suit", "quantity", 1, "unitPrice", 30000))));
        MvcResult created = mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 30000, "method", "Cash"))))
                .andExpect(status().isCreated());

        // KPI cards reflect the new data (one pending order, one new customer today).
        MvcResult sum = mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingOrders.value").value(1))
                .andExpect(jsonPath("$.data.newCustomers.value").value(1))
                .andReturn();
        JsonNode revenue = objectMapper.readTree(sum.getResponse().getContentAsString())
                .path("data").path("revenueToday").path("value");
        assertEquals(0, revenue.decimalValue().compareTo(new BigDecimal("30000")), "today's revenue");

        // Checklist advances: add_customer, create_order, accept_payments now done -> 5 of 6.
        mockMvc.perform(get("/api/v1/dashboard/setup-checklist").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(5));

        // Dismissing the website step completes the checklist.
        mockMvc.perform(post("/api/v1/dashboard/setup-checklist/set_up_website/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(6))
                .andExpect(jsonPath("$.data.steps[4].key").value("set_up_website"))
                .andExpect(jsonPath("$.data.steps[4].done").value(true));
    }

    @Test
    void bookingLifecycleAvailabilityLinkAndPerformance() throws Exception {
        String token = signupVerticalAndLogin("book-a", "owner@book.test", "general");
        String customerId = createCustomerReturningId(token, "Temi Johnson");

        // A slot midweek (computed relative to the current week so "this week" holds).
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate day = monday.plusDays(2);
        OffsetDateTime start = day.atTime(10, 0).atOffset(ZoneOffset.UTC);

        // Create a booking (end auto-derived from the 60-minute default slot).
        String createBody = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId, "service", "Consultation",
                "start", start.toString(), "amount", 50000, "mode", "virtual"));
        MvcResult created = mockMvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customer").value("Temi Johnson"))
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andExpect(jsonPath("$.data.mode").value("virtual"))
                .andReturn();
        String bookingId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Lands on the calendar for that day.
        mockMvc.perform(get("/api/v1/bookings").param("from", day.toString()).param("to", day.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].service").value("Consultation"));

        // Weekly performance reflects the one (non-cancelled) booking + its amount.
        MvcResult perf = mockMvc.perform(get("/api/v1/bookings/performance").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingsThisWeek").value(1))
                .andReturn();
        JsonNode revenue = objectMapper.readTree(perf.getResponse().getContentAsString())
                .path("data").path("revenueProjected");
        assertEquals(0, revenue.decimalValue().compareTo(new BigDecimal("50000")), "projected revenue");

        // Reschedule via PATCH.
        OffsetDateTime moved = day.plusDays(1).atTime(12, 0).atOffset(ZoneOffset.UTC);
        mockMvc.perform(patch("/api/v1/bookings/" + bookingId).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "start", moved.toString(), "end", moved.plusHours(1).toString()))))
                .andExpect(status().isOk());

        // Availability: defaults, then update slot/buffer.
        mockMvc.perform(get("/api/v1/bookings/availability").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slotDurationMinutes").value(60));
        mockMvc.perform(put("/api/v1/bookings/availability").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotDurationMinutes", 30, "bufferMinutes", 15))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slotDurationMinutes").value(30))
                .andExpect(jsonPath("$.data.bufferMinutes").value(15));

        // Shareable link: default slug = tenant slug; regenerate derives a new one.
        mockMvc.perform(get("/api/v1/bookings/link").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("book-a"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.url").value("conddo.io/book/book-a"));
        MvcResult regen = mockMvc.perform(post("/api/v1/bookings/link").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String newSlug = objectMapper.readTree(regen.getResponse().getContentAsString())
                .path("data").path("slug").asText();
        assertTrue(newSlug.startsWith("book-a-"), "regenerated slug keeps the tenant prefix: " + newSlug);

        // Cancel (delete) -> gone.
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/bookings/" + bookingId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicSelfBookCreatesPendingBookingVisibleToOwner() throws Exception {
        String token = signupVerticalAndLogin("book-pub", "owner@bookpub.test", "general");

        // The public booking page resolves by the (default) tenant slug — no auth.
        mockMvc.perform(get("/api/v1/public/book/book-pub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.business").value("book-pub Business"))
                .andExpect(jsonPath("$.data.slotDurationMinutes").value(60));

        // A client self-books -> a pending booking is created without authenticating.
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate day = monday.plusDays(2);
        OffsetDateTime start = day.atTime(11, 0).atOffset(ZoneOffset.UTC);
        mockMvc.perform(post("/api/v1/public/book/book-pub").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerName", "Walk-in Wale", "customerPhone", "+2348030000123",
                                "service", "Consultation", "start", start.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending"));

        // The owner sees the pending booking on their (RLS-scoped) calendar.
        mockMvc.perform(get("/api/v1/bookings").param("from", day.toString()).param("to", day.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customer").value("Walk-in Wale"))
                .andExpect(jsonPath("$.data[0].status").value("pending"));

        // An unknown / disabled slug is a 404.
        mockMvc.perform(get("/api/v1/public/book/no-such-business"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inventoryProductsCategoriesStockAndLowStockKpi() throws Exception {
        String token = signupVerticalAndLogin("inv-a", "owner@inv.test", "general");

        // Create a category, then a product in it with a reorder threshold.
        MvcResult cat = mockMvc.perform(post("/api/v1/inventory/categories").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Fabrics"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Fabrics"))
                .andReturn();
        String categoryId = objectMapper.readTree(cat.getResponse().getContentAsString())
                .path("data").path("id").asText();

        String createBody = objectMapper.writeValueAsString(Map.of(
                "name", "Ankara Roll", "sku", "ANK-001", "categoryId", categoryId,
                "price", 8000, "stock", 10, "reorderThreshold", 3));
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Ankara Roll"))
                .andExpect(jsonPath("$.data.category").value("Fabrics"))
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.lowStock").value(false))
                .andReturn();
        String productId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Filter the list by category.
        mockMvc.perform(get("/api/v1/inventory/products").param("category", categoryId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sku").value("ANK-001"));

        // Sell down below the reorder threshold -> the product is now low-stock.
        mockMvc.perform(post("/api/v1/inventory/products/" + productId + "/adjust").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("delta", -8, "reason", "Sold"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(2))
                .andExpect(jsonPath("$.data.lowStock").value(true));

        // It surfaces in low-stock and drives the dashboard KPI (no longer a placeholder).
        mockMvc.perform(get("/api/v1/inventory/low-stock").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lowStockItems.value").value(1))
                .andExpect(jsonPath("$.data.lowStockItems.tone").value("danger"));

        // Delete -> gone.
        mockMvc.perform(delete("/api/v1/inventory/products/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/inventory/products/" + productId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffInviteListRoleChangeAndDeactivate() throws Exception {
        String token = signupVerticalAndLogin("staff-a", "owner@staff.test", "general");

        // The owner is the only user initially, active after logging in.
        mockMvc.perform(get("/api/v1/staff").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.data[0].status").value("active"));

        // Invite a staff member -> created as "invited"; an invite email is sent.
        MvcResult invited = mockMvc.perform(post("/api/v1/staff/invite").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "mary@staff.test", "role", "STAFF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("invited"))
                .andExpect(jsonPath("$.data.role").value("STAFF"))
                .andReturn();
        String staffId = objectMapper.readTree(invited.getResponse().getContentAsString())
                .path("data").path("id").asText();
        verify(emailSender).send(eq("mary@staff.test"), anyString(), anyString());

        // Promote to admin, then deactivate.
        mockMvc.perform(patch("/api/v1/staff/" + staffId).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("role", "TENANT_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("TENANT_ADMIN"));
        mockMvc.perform(patch("/api/v1/staff/" + staffId).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("inactive"));

        // The role catalogue is available, and duplicate invites are rejected.
        mockMvc.perform(get("/api/v1/staff/roles").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(post("/api/v1/staff/invite").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "mary@staff.test", "role", "STAFF"))))
                .andExpect(status().isConflict());
    }

    @Test
    void settingsProfileBrandingHoursSocialAndDeactivate() throws Exception {
        String token = signupVerticalAndLogin("set-a", "owner@set.test", "fashion");

        // Profile reflects signup; industry + subdomain are read-only passthroughs.
        mockMvc.perform(get("/api/v1/settings/business-profile").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("set-a Business"))
                .andExpect(jsonPath("$.data.industry").value("fashion"))
                .andExpect(jsonPath("$.data.subdomain").value("set-a"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // Update the editable fields; industry stays put.
        mockMvc.perform(put("/api/v1/settings/business-profile").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Glam Adaeze", "tagline", "Bespoke fashion",
                                "email", "hello@glam.test", "phone", "+2348030000200"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Glam Adaeze"))
                .andExpect(jsonPath("$.data.tagline").value("Bespoke fashion"))
                .andExpect(jsonPath("$.data.email").value("hello@glam.test"))
                .andExpect(jsonPath("$.data.industry").value("fashion"));

        // Branding.
        mockMvc.perform(put("/api/v1/settings/branding").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("primaryColor", "#7C3AED", "logoUrl", "https://cdn/x.png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.primaryColor").value("#7C3AED"));

        // Business hours round-trip (JSONB).
        mockMvc.perform(put("/api/v1/settings/business-hours").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mon", Map.of("open", true, "start", "08:00", "end", "18:00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mon.start").value("08:00"));
        mockMvc.perform(get("/api/v1/settings/business-hours").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mon.end").value("18:00"));

        // Social handles.
        mockMvc.perform(put("/api/v1/settings/social-handles").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("instagram", "@glam"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instagram").value("@glam"));

        // Danger Zone: deactivate the tenant.
        mockMvc.perform(post("/api/v1/settings/danger/deactivate").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void analyticsAggregatesOrdersPaymentsAndCustomers() throws Exception {
        String token = signupVerticalAndLogin("an-a", "owner@an.test", "fashion");
        String customerId = createCustomerReturningId(token, "Chidi Benson");

        // Two orders for the same service; record a payment on the first.
        String orderId = createOrder(token, customerId, "Senator Suit", 30000);
        createOrder(token, customerId, "Senator Suit", 20000);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 30000))))
                .andExpect(status().isCreated());

        // Overview: two orders, one new customer, revenue = the payment.
        MvcResult overview = mockMvc.perform(get("/api/v1/analytics/overview").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders").value(2))
                .andExpect(jsonPath("$.data.newCustomers").value(1))
                .andReturn();
        JsonNode revenue = objectMapper.readTree(overview.getResponse().getContentAsString())
                .path("data").path("revenue");
        assertEquals(0, revenue.decimalValue().compareTo(new BigDecimal("30000")), "revenue over the range");

        // Time-series each have a single (today's) bucket.
        mockMvc.perform(get("/api/v1/analytics/revenue").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/analytics/orders").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Leaderboard: the repeated service tops it with a count of 2.
        mockMvc.perform(get("/api/v1/analytics/top").param("metric", "services").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].label").value("Senator Suit"))
                .andExpect(jsonPath("$.data[0].value").value(2));

        // Customer analytics.
        mockMvc.perform(get("/api/v1/analytics/customers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newCustomers").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void globalSearchAndNotificationsBellFeed() throws Exception {
        String token = signupVerticalAndLogin("xc-a", "owner@xc.test", "general");
        String customerId = createCustomerReturningId(token, "Zainab Bello");
        createOrder(token, customerId, "Kaftan", 40000);

        // Global search finds the customer by name and the order by service.
        mockMvc.perform(get("/api/v1/search").param("q", "zainab").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customers.length()").value(1))
                .andExpect(jsonPath("$.data.customers[0].label").value("Zainab Bello"));
        mockMvc.perform(get("/api/v1/search").param("q", "kaftan").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(1));

        // The bell feed starts empty.
        mockMvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // A public self-booking notifies the owner (§11.12 producer).
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime start = monday.plusDays(2).atTime(9, 0).atOffset(ZoneOffset.UTC);
        mockMvc.perform(post("/api/v1/public/book/xc-a").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerName", "Walk-in Wale", "service", "Consultation", "start", start.toString()))))
                .andExpect(status().isCreated());

        MvcResult feed = mockMvc.perform(get("/api/v1/notifications").param("unread", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("BOOKING"))
                .andReturn();
        String notifId = objectMapper.readTree(feed.getResponse().getContentAsString())
                .path("data").path("items").get(0).path("id").asText();

        // Mark it read -> the unread badge clears.
        mockMvc.perform(post("/api/v1/notifications/" + notifId + "/read").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(0));
    }

    @Test
    void marketingPostsAndCampaigns() throws Exception {
        String token = signupVerticalAndLogin("mkt-a", "owner@mkt.test", "fashion");

        // Schedule a post on two platforms.
        OffsetDateTime when = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        MvcResult created = mockMvc.perform(post("/api/v1/marketing/posts").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Serum launch", "content", "New hydrating serum!",
                                "platforms", List.of("instagram", "facebook"),
                                "scheduledAt", when.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.platform").value("instagram"))
                .andExpect(jsonPath("$.data.platforms.length()").value(2))
                .andExpect(jsonPath("$.data.status").value("scheduled"))
                .andReturn();
        String postId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Lists, and the platform filter excludes posts not targeting that channel.
        mockMvc.perform(get("/api/v1/marketing/posts").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/marketing/posts").param("platform", "x").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Publish it.
        mockMvc.perform(post("/api/v1/marketing/posts/" + postId + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("published"));

        // Create an email campaign — starts as a draft with zero stats.
        mockMvc.perform(post("/api/v1/marketing/campaigns").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "May Promo", "type", "email", "audienceSize", 1240))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("email"))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.openRate").value(0.0));

        // Filter campaigns by type.
        mockMvc.perform(get("/api/v1/marketing/campaigns").param("type", "email").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/marketing/campaigns").param("type", "sms").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // An invalid campaign type is rejected.
        mockMvc.perform(post("/api/v1/marketing/campaigns").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Bad", "type", "carrier-pigeon"))))
                .andExpect(status().isConflict());
    }

    @Test
    void marketingLeadsFunnelConnectionsAndSummary() throws Exception {
        String token = signupVerticalAndLogin("mkt-b", "owner@mktb.test", "fashion");

        // Two leads; move one to converted.
        MvcResult lead = mockMvc.perform(post("/api/v1/marketing/leads").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Lead One"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.stage").value("new"))
                .andReturn();
        String leadId = objectMapper.readTree(lead.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mockMvc.perform(post("/api/v1/marketing/leads").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "Lead Two"))))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/v1/marketing/leads/" + leadId).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("stage", "converted"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("converted"));

        // Funnel: 4 stages in order, 1 of 2 converted -> 50%.
        mockMvc.perform(get("/api/v1/marketing/leads/funnel").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stages.length()").value(4))
                .andExpect(jsonPath("$.data.stages[0].stage").value("new"))
                .andExpect(jsonPath("$.data.conversionRate").value(50.0));

        // Filter leads by stage.
        mockMvc.perform(get("/api/v1/marketing/leads").param("stage", "converted").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Connect a social account; re-connecting the same platform updates the handle (idempotent).
        mockMvc.perform(post("/api/v1/marketing/connections").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("platform", "instagram", "handle", "@glam"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.platform").value("instagram"));
        mockMvc.perform(post("/api/v1/marketing/connections").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("platform", "instagram", "handle", "@glam2"))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/marketing/connections").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].handle").value("@glam2"));

        // Overview summary reflects the lead count.
        mockMvc.perform(get("/api/v1/marketing/summary").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newLeads.value").value(2))
                .andExpect(jsonPath("$.data.emailOpenRate.value").value(0.0));
    }

    @Test
    void accessTokenCarriesVerticalPlanAndActiveModules() throws Exception {
        String token = signupVerticalAndLogin("claims-a", "owner@claims.test", "fashion");

        JsonNode claims = decodeJwtClaims(token);
        assertEquals("fashion", claims.path("vertical").asText());
        assertEquals("starter", claims.path("plan").asText());   // no plan set -> normalised to starter
        String modules = claims.path("activeModules").toString();
        assertTrue(modules.contains("\"website\""), "activeModules has website: " + modules);
        assertTrue(modules.contains("\"crm\""), "activeModules has crm: " + modules);
        assertTrue(modules.contains("\"orders.fashion\""), "fashion starter has orders.fashion: " + modules);
    }

    @Test
    void registryManifestsBuildNavFromActiveModules() throws Exception {
        String token = signupVerticalAndLogin("reg-a", "owner@reg.test", "fashion");

        // Two marketing tools must collapse to a single Marketing nav section.
        mockMvc.perform(get("/api/v1/registry/manifests")
                        .param("modules", "website,crm,orders.fashion,marketing.social,marketing.email,analytics")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))   // website, customers, orders, marketing, analytics
                .andExpect(jsonPath("$.data[0].navItem.label").value("Website"))
                .andExpect(jsonPath("$.data[0].navItem.path").value("/website"))
                .andExpect(jsonPath("$.data[1].navItem.label").value("Customers"))
                .andExpect(jsonPath("$.data[3].toolId").value("marketing"))
                .andExpect(jsonPath("$.data[3].navItem.path").value("/marketing"));
    }

    @Test
    void customerOrderAndPaymentHistory() throws Exception {
        String token = signupVerticalAndLogin("hist-a", "owner@hist.test", "fashion");
        String customerId = createCustomerReturningId(token, "Repeat Buyer");
        String orderId = createOrder(token, customerId, "Gown", 50000);
        createOrder(token, customerId, "Suit", 30000);
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 20000, "method", "Cash"))))
                .andExpect(status().isCreated());

        // Order history: both orders, newest first, with the snapshot customer name.
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/orders").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].customer").value("Repeat Buyer"));

        // Payment history across the customer's orders.
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].method").value("Cash"));

        // Unknown customer -> 404.
        mockMvc.perform(get("/api/v1/customers/" + java.util.UUID.randomUUID() + "/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicTenantResolvesFromSubdomainHost() throws Exception {
        signupVerticalAndLogin("dom-a", "owner@dom.test", "fashion");

        // A request on the tenant's subdomain resolves to its public identity — no auth.
        mockMvc.perform(get("/api/v1/public/tenant").header("X-Forwarded-Host", "dom-a.conddo.io"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("dom-a"))
                .andExpect(jsonPath("$.data.name").value("dom-a Business"))
                .andExpect(jsonPath("$.data.vertical").value("fashion"));

        // Unknown, reserved, and apex hosts all fail closed (404).
        mockMvc.perform(get("/api/v1/public/tenant").header("X-Forwarded-Host", "nope.conddo.io"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/tenant").header("X-Forwarded-Host", "api.conddo.io"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/tenant").header("X-Forwarded-Host", "conddo.io"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paymentsSummaryTransactionsAndOutstanding() throws Exception {
        String token = signupVerticalAndLogin("pay-co", "owner@pay.test", "fashion");

        // A customer (with a phone, for the reminder), one unpaid order and one fully-paid order.
        MvcResult cust = mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", "Chidi Benson", "phone", "08030001111"))))
                .andExpect(status().isCreated()).andReturn();
        String customerId = objectMapper.readTree(cust.getResponse().getContentAsString())
                .path("data").path("id").asText();

        createOrder(token, customerId, "Senator Suit", 30000);            // unpaid -> outstanding 30000
        String paidOrder = createOrder(token, customerId, "Gown", 20000); // fully paid below
        mockMvc.perform(post("/api/v1/orders/" + paidOrder + "/payments").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 20000, "method", "Transfer"))))
                .andExpect(status().isCreated());

        // Summary KPIs computed from orders + payments.
        MvcResult sum = mockMvc.perform(get("/api/v1/payments/summary").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidInvoices").value(1))
                .andReturn();
        JsonNode data = objectMapper.readTree(sum.getResponse().getContentAsString()).path("data");
        assertEquals(0, data.path("thisMonth").decimalValue().compareTo(new BigDecimal("20000")), "this month");
        assertEquals(0, data.path("outstanding").decimalValue().compareTo(new BigDecimal("30000")), "outstanding");
        assertEquals(0, data.path("overdue").decimalValue().compareTo(BigDecimal.ZERO), "overdue (none due)");

        // Transactions: a received row + an outstanding row; filters narrow each.
        mockMvc.perform(get("/api/v1/payments/transactions").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2));
        mockMvc.perform(get("/api/v1/payments/transactions").param("filter", "received")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("received"))
                .andExpect(jsonPath("$.data[0].method").value("Transfer"));
        mockMvc.perform(get("/api/v1/payments/transactions").param("filter", "outstanding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("outstanding"));

        // Outstanding grouped by customer, with the reminder target.
        mockMvc.perform(get("/api/v1/payments/outstanding").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Chidi Benson"))
                .andExpect(jsonPath("$.data[0].customerId").value(customerId))
                .andExpect(jsonPath("$.data[0].tone").value("warning"));

        // Reminder sends an SMS to the customer.
        mockMvc.perform(post("/api/v1/payments/reminders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("customerId", customerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true));
        verify(smsSender, atLeastOnce()).send(eq("08030001111"), anyString());
    }

    @Test
    void websiteConfigStatusSectionsAndChangeRequests() throws Exception {
        String token = signupVerticalAndLogin("web-co", "owner@web.test", "fashion");

        // Site config: subdomain from the slug, not yet published.
        mockMvc.perform(get("/api/v1/website").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subdomain").value("web-co"))
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"));

        // Status widget resolves the default *.conddo.io domain; in-progress until live.
        mockMvc.perform(get("/api/v1/website/status").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("in_progress"))
                .andExpect(jsonPath("$.data.domain").value("web-co.conddo.io"))
                .andExpect(jsonPath("$.data.visitsToday").value(0));

        // Sections fall back to the fashion vertical's default layout.
        mockMvc.perform(get("/api/v1/website/sections").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("hero"))
                .andExpect(jsonPath("$.data[0].configured").value(false));

        mockMvc.perform(get("/api/v1/website/analytics").param("range", "7d")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("7d"))
                .andExpect(jsonPath("$.data.visits").value(0));

        // Request an edit -> recorded PENDING, then listed.
        mockMvc.perform(post("/api/v1/website/change-requests").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("area", "hero", "details", "Make my logo bigger"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.details").value("Make my logo bigger"));
        mockMvc.perform(get("/api/v1/website/change-requests").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Connect a custom domain; the status widget then serves it. Bad input is rejected.
        mockMvc.perform(post("/api/v1/website/domain").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", "shop.example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customDomain").value("shop.example.com"));
        mockMvc.perform(get("/api/v1/website/status").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("shop.example.com"));
        mockMvc.perform(post("/api/v1/website/domain").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("domain", "not a domain"))))
                .andExpect(status().isConflict());
    }

    @Test
    void verticalConfigCoversAllSevenVerticals() throws Exception {
        String token = signupVerticalAndLogin("vert-co", "owner@vert.test", "fashion");

        // Every canonical vertical resolves with non-empty stages.
        for (String id : List.of("fashion", "pharmacy", "logistics", "retail",
                "professional-services", "food-and-beverage", "beauty-and-wellness", "general", "default")) {
            mockMvc.perform(get("/api/v1/verticals/" + id + "/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.equals("default") ? "general" : id))
                    .andExpect(jsonPath("$.data.orderStages.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        }

        // A couple of vertical-specific shapes (newly added verticals).
        mockMvc.perform(get("/api/v1/verticals/logistics/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.data.measurementFields[0].key").value("weight"))
                .andExpect(jsonPath("$.data.measurementFields[0].unit").value("kg"));
        mockMvc.perform(get("/api/v1/verticals/food-and-beverage/config").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.data.websiteSections", org.hamcrest.Matchers.hasItem("menu")));
    }

    @Test
    void mediaUploadListFetchAndDelete() throws Exception {
        String token = signupVerticalAndLogin("media-co", "owner@media.test", "fashion");

        // Upload an image -> 201 with a presigned url + metadata.
        MockMultipartFile file = new MockMultipartFile(
                "file", "My Logo!.png", "image/png", new byte[]{1, 2, 3, 4, 5});
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/media").file(file).param("purpose", "logo")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.url").exists())
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.kind").value("logo"))
                .andExpect(jsonPath("$.data.size").value(5))
                .andReturn();
        String mediaId = objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // It shows in the tenant's library and is fetchable by id.
        mockMvc.perform(get("/api/v1/media").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1));
        mockMvc.perform(get("/api/v1/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("My Logo!.png"));

        // A non-image/pdf upload is rejected.
        MockMultipartFile bad = new MockMultipartFile(
                "file", "app.exe", "application/octet-stream", new byte[]{9, 9});
        mockMvc.perform(multipart("/api/v1/media").file(bad).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict());

        // Tenant isolation: another tenant can't see or fetch it.
        String other = signupVerticalAndLogin("media-b", "owner@media-b.test", "fashion");
        mockMvc.perform(get("/api/v1/media").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound());

        // Delete removes it.
        mockMvc.perform(delete("/api/v1/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/media/" + mediaId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantSignupAutoCreatesStudioJobWithResolvedWebsiteType() throws Exception {
        // Fashion + starter → LANDING_PAGE (a simple single-page site).
        StudioGatewayTestConfig.lastBrief = null;
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Glam Co", "slug", "auto-glam",
                                "verticalId", "fashion", "planId", "starter",
                                "adminEmail", "owner@auto-glam.test", "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());

        awaitStudioGatewayCall();
        assertNotNull(StudioGatewayTestConfig.lastBrief,
                "TenantActivationListener should have called the Studio gateway");
        assertEquals("WEBSITE_BUILD", StudioGatewayTestConfig.lastJobType);
        assertEquals("Website Build — Glam Co", StudioGatewayTestConfig.lastTitle);
        assertEquals("LANDING_PAGE", StudioGatewayTestConfig.lastBrief.get("websiteType"));
        assertEquals("fashion", StudioGatewayTestConfig.lastBrief.get("vertical"));
        assertEquals("starter", StudioGatewayTestConfig.lastBrief.get("plan"));
        assertEquals("tenant-activated", StudioGatewayTestConfig.lastBrief.get("source"));
        assertTrue(((java.util.List<?>) StudioGatewayTestConfig.lastBrief.get("recommendedSections"))
                .contains("hero"));

        // Beauty/wellness routes to BOOKING_FOCUSED regardless of plan.
        StudioGatewayTestConfig.lastBrief = null;
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Bella Spa", "slug", "auto-bella",
                                "verticalId", "beauty-and-wellness", "planId", "business",
                                "adminEmail", "owner@auto-bella.test", "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
        awaitStudioGatewayCall();
        assertEquals("BOOKING_FOCUSED", StudioGatewayTestConfig.lastBrief.get("websiteType"));

        // Pro retail routes to ECOMMERCE.
        StudioGatewayTestConfig.lastBrief = null;
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Mega Mart", "slug", "auto-mart",
                                "verticalId", "retail", "planId", "pro",
                                "adminEmail", "owner@auto-mart.test", "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
        awaitStudioGatewayCall();
        assertEquals("ECOMMERCE", StudioGatewayTestConfig.lastBrief.get("websiteType"));
    }

    /** Polls up to 5s for the {@code @Async} TenantActivationListener to call the gateway. */
    private static void awaitStudioGatewayCall() throws InterruptedException {
        for (int i = 0; i < 50 && StudioGatewayTestConfig.lastBrief == null; i++) {
            Thread.sleep(100);
        }
    }

    // ----- Google Sign-in (§1a) -----------------------------------------------

    @Test
    void googleLoginFailsWhenTokenCannotBeVerified() throws Exception {
        // Stub returns null → verifier returns empty → 400 GOOGLE_ID_TOKEN_INVALID.
        GoogleVerifierTestConfig.stub(null);
        mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("idToken", "fake.token", "tenantSlug", "anything"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ID_TOKEN_INVALID"));
    }

    @Test
    void googleLoginFailsWhenEmailUnverified() throws Exception {
        GoogleVerifierTestConfig.stub(new io.conddo.core.auth.GoogleIdentity(
                "google-sub-1", "owner@example.com", false, "Owner"));
        mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("idToken", "fake.token", "tenantSlug", "anything"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_EMAIL_UNVERIFIED"));
    }

    @Test
    void googleLoginLinksExistingPasswordUserAndIssuesTokens() throws Exception {
        String slug = "google-link";
        String email = "owner@" + slug + ".test";
        signup(slug, email);   // existing password-based user, no google_sub yet

        GoogleVerifierTestConfig.stub(new io.conddo.core.auth.GoogleIdentity(
                "google-sub-link-1", email, true, "Owner"));

        MvcResult login = mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("idToken", "fake.token", "tenantSlug", slug))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "google login must also issue the refresh cookie");

        // Verify the user row was actually linked (read as owner to bypass RLS).
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT google_sub FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("google-sub-link-1", rs.getString(1),
                        "first-time Google sign-in should atomically write google_sub");
            }
        }
    }

    @Test
    void googleLoginUnknownUserIs404() throws Exception {
        GoogleVerifierTestConfig.stub(new io.conddo.core.auth.GoogleIdentity(
                "google-sub-unknown", "ghost@example.com", true, "Ghost"));
        mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("idToken", "fake.token", "tenantSlug", "nope"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void googleStartRegisterIsRejectedOnUnverifiedEmail() throws Exception {
        GoogleVerifierTestConfig.stub(new io.conddo.core.auth.GoogleIdentity(
                "google-sub-reg", "new-owner@example.com", false, "New Owner"));
        mockMvc.perform(post("/auth/register/start-google").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("idToken", "fake.token", "phone", "+2349011111111"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_EMAIL_UNVERIFIED"));
    }

    // ----- helpers ---------------------------------------------------------

    private void signup(String slug, String adminEmail) throws Exception {
        signupReturningId(slug, adminEmail);
    }

    /** Starts a staged registration and returns its id. */
    private String registerStart(String fullName, String phone, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register/start").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", fullName, "phone", phone, "email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationId").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("registrationId").asText();
    }

    /** Counts audit_log rows for a tenant + action, read as the owner (bypasses RLS). */
    private long countAudit(String tenantId, String action) throws SQLException {
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT count(*) FROM audit_log WHERE tenant_id = ?::uuid AND action = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, action);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Pulls the "selector.verifier" reset token out of a reset email body. */
    private String extractResetToken(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\w-]{8,}\\.[\\w-]{8,}").matcher(body);
        assertTrue(matcher.find(), "no reset token in email body: " + body);
        return matcher.group();
    }

    /**
     * Extracts the 4-digit code from the (stubbed) OTP email. Signup OTP goes by
     * email via the branded HTML template, so it's delivered through
     * {@code sendHtml(to, subject, html, text)} — capture the text fallback (arg 4).
     */
    private String capturedOtp(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).sendHtml(eq(email), anyString(), anyString(), body.capture());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{4}").matcher(body.getValue());
        assertTrue(matcher.find(), "no OTP code in email: " + body.getValue());
        return matcher.group();
    }

    private String signupReturningId(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    /** Signs up a tenant on a given vertical (drives the default order pipeline) and logs in. */
    private String signupVerticalAndLogin(String slug, String email, String verticalId) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", slug + " Business", "slug", slug, "verticalId", verticalId,
                                "adminEmail", email, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
        return login(slug, email, PASSWORD);
    }

    private MvcResult performLogin(String slug, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantSlug", slug, "email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "login must set the refresh cookie");
        assertTrue(setCookie.contains(RefreshCookies.COOKIE_NAME + "="), "wrong cookie name");
        assertTrue(setCookie.contains("HttpOnly"), "refresh cookie must be HttpOnly");
        assertTrue(setCookie.contains("SameSite=Strict"), "refresh cookie must be SameSite=Strict");
        return result;
    }

    private String login(String slug, String email, String password) throws Exception {
        JsonNode body = objectMapper.readTree(performLogin(slug, email, password).getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    /** Logs in an internal staff member (no tenant slug, no refresh cookie). */
    private String staffLogin(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/staff/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    /** Extracts the refresh-cookie value from a response's Set-Cookie header. */
    private String refreshCookieValue(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        String prefix = RefreshCookies.COOKIE_NAME + "=";
        int start = setCookie.indexOf(prefix) + prefix.length();
        int end = setCookie.indexOf(';', start);
        return setCookie.substring(start, end < 0 ? setCookie.length() : end);
    }

    private void createCustomer(String token, String fullName) throws Exception {
        createCustomerReturningId(token, fullName);
    }

    /** Creates a customer and returns the new id from the profile response. */
    private String createCustomerReturningId(String token, String fullName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", fullName))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    /** Creates an order (no line items, explicit amount) and returns its id. */
    private String createOrder(String token, String customerId, String service, int amount) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "customerId", customerId, "service", service, "amount", amount));
        MvcResult result = mockMvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asText();
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Decodes a JWT's payload (claims) without verifying the signature. */
    private JsonNode decodeJwtClaims(String token) throws Exception {
        String payload = token.split("\\.")[1];
        return objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(payload));
    }
}
