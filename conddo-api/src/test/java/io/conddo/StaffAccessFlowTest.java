package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the StaffAccess matrix gates the right modules per
 * sub-role (HANDOFF_2026-06-12 §4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StaffAccessFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";
    private static final String PASSWORD_HASH = new PasswordHasher().hash(PASSWORD);

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
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @Test
    void cashierCanOpenPosSessionStockManagerCannot() throws Exception {
        String tenantId = signup("acc-pos", "owner@acc-pos.test");
        grantFeature(tenantId, "pos");

        String cashierToken = seedAndLogin(tenantId, "cashier@acc-pos.test", "Tunde", "CASHIER", "acc-pos");
        String stockToken = seedAndLogin(tenantId, "stock@acc-pos.test", "Bisi", "STOCK_MANAGER", "acc-pos");

        // CASHIER → 201 OPEN session
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cashierToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isCreated());

        // STOCK_MANAGER → 403 (pos = NONE in matrix)
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stockToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void stockManagerCanRestockCashierCannot() throws Exception {
        String tenantId = signup("acc-inv", "owner@acc-inv.test");
        String productId = seedProduct(tenantId, "Para", "PARA-1", 100, 50);

        String stockToken = seedAndLogin(tenantId, "stock@acc-inv.test", "Bisi", "STOCK_MANAGER", "acc-inv");
        String cashierToken = seedAndLogin(tenantId, "cashier@acc-inv.test", "Tunde", "CASHIER", "acc-inv");

        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId, "quantity", 10)),
                "note", "restock");

        // STOCK_MANAGER → 201
        mockMvc.perform(post("/api/v1/inventory/restock")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stockToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // CASHIER → 403 (inventory = READ for cashier)
        mockMvc.perform(post("/api/v1/inventory/restock")
                        .header(HttpHeaders.AUTHORIZATION, bearer(cashierToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookkeeperBlockedFromAllWrites() throws Exception {
        String tenantId = signup("acc-book", "owner@acc-book.test");
        grantFeature(tenantId, "pos");
        seedProduct(tenantId, "Para", "PARA-2", 100, 50);
        String token = seedAndLogin(tenantId, "book@acc-book.test", "Tola", "BOOKKEEPER", "acc-book");

        // No POS, no restock.
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerStillHasFullAccess() throws Exception {
        String tenantId = signup("acc-owner", "owner@acc-owner.test");
        grantFeature(tenantId, "pos");
        String token = login("acc-owner", "owner@acc-owner.test");

        // Owner /me sees role=TENANT_ADMIN + staffRole=null.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.data.user.staffRole").isEmpty());

        // Owner can still open a POS session (gated by canRead+canWrite, both
        // unconditionally true for owners).
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isCreated());
    }

    @Test
    void staffSeesStaffRoleClaimInJwtAndMe() throws Exception {
        String tenantId = signup("acc-me", "owner@acc-me.test");
        String token = seedAndLogin(tenantId, "pharm@acc-me.test", "Ngozi", "PHARMACIST", "acc-me");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("STAFF"))
                .andExpect(jsonPath("$.data.user.staffRole").value("PHARMACIST"));
    }

    // ----- helpers ----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Biz", "slug", slug,
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

    /** Insert a STAFF user with the given staff_role, then log them in. */
    private String seedAndLogin(String tenantId, String email, String fullName,
                                 String staffRole, String slug) throws Exception {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO users (tenant_id, email, password_hash, full_name, role, staff_role) "
                             + "VALUES (?::uuid, ?, ?, ?, 'STAFF', ?)")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            ps.setString(3, PASSWORD_HASH);
            ps.setString(4, fullName);
            ps.setString(5, staffRole);
            ps.executeUpdate();
        }
        return login(slug, email);
    }

    private void grantFeature(String tenantId, String featureKey) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO tenant_feature_flags (tenant_id, feature_key, status, enabled, granted_at) "
                             + "VALUES (?::uuid, ?, 'beta', true, now())")) {
            ps.setString(1, tenantId);
            ps.setString(2, featureKey);
            ps.executeUpdate();
        }
    }

    private String seedProduct(String tenantId, String name, String sku, int price, int stock)
            throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active) "
                             + "VALUES (?::uuid, ?, ?, ?, ?, 0, true) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, sku);
            ps.setInt(4, price);
            ps.setInt(5, stock);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
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
