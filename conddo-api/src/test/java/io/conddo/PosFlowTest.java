package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS Phase 1 end-to-end: session lifecycle, cart, payments, complete
 * with stock decrement, conflict cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PosFlowTest {

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_password";
    private static final String PASSWORD = "password123";

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
    void happyPathOpenSessionRingUpSaleCompleteDecrementsStock() throws Exception {
        String tenantId = signup("pos-happy", "owner@pos-happy.test");
        grantFeature(tenantId, "pos");
        String token = login("pos-happy", "owner@pos-happy.test");
        String productId = seedProduct(tenantId, "Paracetamol 500mg", "PARA-500", 150, 100);

        // Open session
        MvcResult sessionRes = mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "openingFloat", 5000, "notes", "Morning"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn();

        // Open sale
        MvcResult saleRes = mockMvc.perform(post("/api/v1/pos/sales")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andReturn();
        String saleId = readField(saleRes, "$.data.id");

        // Add 2 items
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", productId, "qty", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotal").value(300.0))
                .andExpect(jsonPath("$.data.total").value(300.0))
                .andExpect(jsonPath("$.data.balance").value(300.0));

        // Take cash payment
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "method", "CASH", "amount", 300))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paid").value(300.0))
                .andExpect(jsonPath("$.data.balance").value(0.0));

        // Complete
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.receipt.total").value(300.0))
                .andExpect(jsonPath("$.data.receipt.change").value(0.0));

        // Stock decremented
        assertEquals(98, readStock(productId),
                "Completing a 2-qty sale must decrement stock by 2");
        // SALE_POS movement landed in the audit ledger
        assertTrue(hasMovement(productId, "SALE_POS", -2, 100, 98),
                "Completing must write a SALE_POS movement to pharmacy_stock_movements");
    }

    @Test
    void openingSecondSessionForSameCashierConflicts() throws Exception {
        String tenantId = signup("pos-second", "owner@pos-second.test");
        grantFeature(tenantId, "pos");
        String token = login("pos-second", "owner@pos-second.test");

        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_ALREADY_OPEN"));
    }

    @Test
    void insufficientPaymentBlocksComplete() throws Exception {
        String tenantId = signup("pos-short", "owner@pos-short.test");
        grantFeature(tenantId, "pos");
        String token = login("pos-short", "owner@pos-short.test");
        String productId = seedProduct(tenantId, "Vitamin C", "VITC-1G", 200, 20);

        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 1000))))
                .andExpect(status().isCreated());

        MvcResult saleRes = mockMvc.perform(post("/api/v1/pos/sales")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = readField(saleRes, "$.data.id");

        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", productId, "qty", 3))))
                .andExpect(status().isOk());

        // Pay only half
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "method", "CASH", "amount", 300))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_INSUFFICIENT"));

        // Stock untouched
        assertEquals(20, readStock(productId),
                "Failed completion must not decrement stock");
    }

    @Test
    void closeSessionComputesExpectedCashAndVariance() throws Exception {
        String tenantId = signup("pos-close", "owner@pos-close.test");
        grantFeature(tenantId, "pos");
        String token = login("pos-close", "owner@pos-close.test");
        String productId = seedProduct(tenantId, "Loratadine", "LORA-10", 500, 50);

        // Open session with ₦2000 float
        mockMvc.perform(post("/api/v1/pos/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("openingFloat", 2000))))
                .andExpect(status().isCreated());

        // Run one sale: ₦1000 cash
        MvcResult saleRes = mockMvc.perform(post("/api/v1/pos/sales")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        String saleId = readField(saleRes, "$.data.id");
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", productId, "qty", 2))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "method", "CASH", "amount", 1000))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/pos/sales/" + saleId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // Read session id and close it
        String sessionId = readOpenSessionId(tenantId);
        mockMvc.perform(post("/api/v1/pos/sessions/" + sessionId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "countedCash", 3050))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.expectedCash").value(3000.0))   // 2000 float + 1000 cash sale
                .andExpect(jsonPath("$.data.countedCash").value(3050.0))
                .andExpect(jsonPath("$.data.cashVariance").value(50.0));
    }

    // ----- helpers ----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Pharmacy", "slug", slug,
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

    private int readStock(String productId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT stock FROM products WHERE id = ?::uuid")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private boolean hasMovement(String productId, String type, int delta, int before, int after)
            throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT 1 FROM pharmacy_stock_movements "
                             + "WHERE product_id = ?::uuid AND movement_type = ? "
                             + "AND quantity_change = ? AND quantity_before = ? AND quantity_after = ?")) {
            ps.setString(1, productId);
            ps.setString(2, type);
            ps.setInt(3, delta);
            ps.setInt(4, before);
            ps.setInt(5, after);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String readOpenSessionId(String tenantId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT id FROM pos_sessions WHERE tenant_id = ?::uuid AND status = 'OPEN'")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String readField(MvcResult result, String jsonPath) throws Exception {
        JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
        // crude $.data.id navigation
        String[] parts = jsonPath.replace("$.", "").split("\\.");
        JsonNode node = tree;
        for (String p : parts) {
            node = node.path(p);
        }
        return node.asText();
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
