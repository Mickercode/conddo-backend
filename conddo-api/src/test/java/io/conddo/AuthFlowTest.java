package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.api.security.JwtTenantContextFilter;
import io.conddo.api.security.RefreshCookies;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.notify.NotificationPort;
import io.conddo.core.notify.SmsSender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private NotificationPort notificationPort;
    @MockBean
    private SmsSender smsSender;

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
                .andExpect(jsonPath("$.data[0].fullName").value("Alice"));
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
                .andExpect(jsonPath("$.data[0].fullName").value("Alice-A"));
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fullName").value("Bob-B"));
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

        // The stub "delivers" the token; capture it to complete the reset.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationPort).sendPasswordReset(eq("owner@pr.test"), tokenCaptor.capture());

        mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", tokenCaptor.getValue(), "newPassword", "brand-new-pass"))))
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
                .andExpect(jsonPath("$.data[0].fullName").value("Alice-SA"));

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
                .andExpect(jsonPath("$.data[0].fullName").value("Alice-SA"));
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
        String regId = registerStart("Amaka", phone, "amaka@biz.test");

        // Verify with the code the (stubbed) SMS "sent".
        mockMvc.perform(post("/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("registrationId", regId, "code", capturedOtp(phone)))))
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
        String phone = "+2348030000002";
        String regId = registerStart("Bola", phone, "bola@biz.test");
        String wrong = capturedOtp(phone).equals("0000") ? "1111" : "0000";

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

    /** Extracts the 4-digit code from the (stubbed) SMS sent to a phone. */
    private String capturedOtp(String phone) {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(smsSender, atLeastOnce()).send(eq(phone), message.capture());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{4}").matcher(message.getValue());
        assertTrue(matcher.find(), "no OTP code in SMS: " + message.getValue());
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
        mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", fullName))))
                .andExpect(status().isCreated());
    }

    private String json(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
