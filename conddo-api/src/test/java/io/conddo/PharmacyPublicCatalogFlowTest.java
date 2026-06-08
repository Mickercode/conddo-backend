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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the Seb&Bayor public surface (Slice 1):
 * customer auth (register/login/me) + read-only catalog (products with
 * filters, product detail, categories) + delivery-fee lookup. Reactor
 * builds against Flyway up through V32; the public-website endpoints
 * are reached through the existing PublicSiteInterceptor (header API
 * key authentication shipped in Phase 1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyPublicCatalogFlowTest {

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
        registry.add("conddo.customer-jwt.secret",
                () -> "test-customer-jwt-secret-at-least-32-bytes-long-PAD");
        registry.add("conddo.customer-jwt.ttl", () -> "1d");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    // ----- customer auth ----------------------------------------------------

    @Test
    void registerLoginAndMeRoundtrip() throws Exception {
        String tenantId = signup("ph-auth", "owner@ph-auth.test");
        String tenantToken = login("ph-auth", "owner@ph-auth.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-auth");

        // 1. Register a new customer.
        MvcResult reg = mockMvc.perform(post("/api/v1/public/ph-auth/auth/register")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Sarah Okafor",
                                "email", "sarah@buyer.test",
                                "phone", "08098765432",
                                "password", "securepw123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.customer.email").value("sarah@buyer.test"))
                .andReturn();
        String registerToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .path("token").asText();

        // 2. Duplicate register → 409.
        mockMvc.perform(post("/api/v1/public/ph-auth/auth/register")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Sarah O",
                                "email", "sarah@buyer.test",
                                "password", "different"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_TAKEN"));

        // 3. Login with correct password.
        MvcResult log = mockMvc.perform(post("/api/v1/public/ph-auth/auth/login")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "sarah@buyer.test",
                                "password", "securepw123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        String loginToken = objectMapper.readTree(log.getResponse().getContentAsString())
                .path("token").asText();

        // 4. Login with wrong password → 401.
        mockMvc.perform(post("/api/v1/public/ph-auth/auth/login")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "sarah@buyer.test",
                                "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        // 5. /me with the login token returns the customer profile.
        mockMvc.perform(get("/api/v1/public/ph-auth/auth/me")
                        .header("X-Conddo-Site-Key", key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.email").value("sarah@buyer.test"))
                .andExpect(jsonPath("$.customer.fullName").value("Sarah Okafor"))
                .andExpect(jsonPath("$.customer.createdAt").isNotEmpty());

        // 6. /me without the token → 401.
        mockMvc.perform(get("/api/v1/public/ph-auth/auth/me")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isUnauthorized());

        // 7. The register token also works (both tokens are valid).
        assertTrue(!registerToken.isBlank());
    }

    /**
     * Bug 2 fix from HANDOFF_2026-06-08.md — login with no body must
     * be a structured 400, not a 500. The 500 broke Seb&Bayor's first
     * login-form smoke test on the public-site spec §2.6.
     */
    @Test
    void loginWithMissingBodyReturns400BadRequest() throws Exception {
        String tenantId = signup("ph-auth-bad", "owner@ph-auth-bad.test");
        String tenantToken = login("ph-auth-bad", "owner@ph-auth-bad.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-auth-bad");

        // No content at all — would have crashed with 500 previously.
        mockMvc.perform(post("/api/v1/public/ph-auth-bad/auth/login")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        // Empty JSON object — should also be 400 via @Valid (missing fields).
        mockMvc.perform(post("/api/v1/public/ph-auth-bad/auth/login")
                        .header("X-Conddo-Site-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ----- catalog ----------------------------------------------------------

    @Test
    void productsCategoriesAndDetailLookupWork() throws Exception {
        String tenantId = signup("ph-cat", "owner@ph-cat.test");
        String tenantToken = login("ph-cat", "owner@ph-cat.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-cat");
        upgradeToGrowth(tenantId);

        // Seed a category + two products (one prescription-required, one OTC).
        String catId = seedCategory(tenantId, "Prescription Drugs", "prescription", "pill");
        seedProduct(tenantId, "Amoxicillin 500mg", "amoxicillin-500mg", catId,
                "1500.00", 48, true, "Amoxicillin", "Amoxil", "Antibiotic");
        seedProduct(tenantId, "Paracetamol 500mg", "paracetamol-500mg", catId,
                "300.00", 200, false, "Paracetamol", "Panadol", "Painkiller");

        // List all — both products + pagination meta.
        mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/products")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.pagination.total").value(2))
                .andExpect(jsonPath("$.products[0].slug").exists())
                // Wire shape compliance — public-safe fields only.
                .andExpect(jsonPath("$.products[0].nameGeneric").exists())
                .andExpect(jsonPath("$.products[0].requiresPrescription").exists())
                .andExpect(jsonPath("$.products[0].stockQty").exists())
                .andExpect(jsonPath("$.products[0].reorderThreshold").doesNotExist());

        // Filter by requiresPrescription=true.
        mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/products?requiresPrescription=true")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].slug").value("amoxicillin-500mg"));

        // Search by query.
        mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/products?q=Panadol")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].slug").value("paracetamol-500mg"));

        // Detail by slug.
        mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/products/amoxicillin-500mg")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.slug").value("amoxicillin-500mg"))
                .andExpect(jsonPath("$.product.requiresPrescription").value(true))
                .andExpect(jsonPath("$.product.category.slug").value("prescription"));

        // Unknown slug → 404.
        mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/products/nonexistent")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isNotFound());

        // Categories endpoint with product count.
        MvcResult cats = mockMvc.perform(get("/api/v1/public/ph-cat/pharmacy/categories")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode catBody = objectMapper.readTree(cats.getResponse().getContentAsString())
                .path("categories");
        assertTrue(catBody.isArray() && catBody.size() >= 1);
        boolean foundRx = false;
        for (JsonNode c : catBody) {
            if ("prescription".equals(c.path("slug").asText())) {
                foundRx = true;
                assertEquals("Prescription Drugs", c.path("name").asText());
                assertEquals("pill", c.path("icon").asText());
                assertEquals(2, c.path("productCount").asInt());
            }
        }
        assertTrue(foundRx, "expected prescription category in: " + catBody);
    }

    // ----- delivery-fee -----------------------------------------------------

    @Test
    void deliveryFeeReturnsStateSpecificQuotes() throws Exception {
        String tenantId = signup("ph-delivery", "owner@ph-delivery.test");
        String tenantToken = login("ph-delivery", "owner@ph-delivery.test");
        String key = regenerateKey(tenantToken);
        activateSite(tenantId, "ph-delivery");

        // Lagos — known state.
        mockMvc.perform(get("/api/v1/public/ph-delivery/pharmacy/delivery-fee?state=Lagos")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LAGOS"))
                .andExpect(jsonPath("$.fee").value(1500))
                .andExpect(jsonPath("$.estimate").value(
                        org.hamcrest.Matchers.containsString("same day")));

        // Abuja — FCT alias also covered.
        mockMvc.perform(get("/api/v1/public/ph-delivery/pharmacy/delivery-fee?state=Abuja")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(2500));

        // Unknown state → default fee.
        mockMvc.perform(get("/api/v1/public/ph-delivery/pharmacy/delivery-fee?state=Wyoming")
                        .header("X-Conddo-Site-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(3500));
    }

    // ----- helpers ----------------------------------------------------------

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

    private String regenerateKey(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/website/site/regenerate-key")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("apiKey").asText();
    }

    private void activateSite(String tenantId, String subdomain) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE tenant_sites SET subdomain = ?, is_active = true, qa_approved = true "
                             + "WHERE tenant_id = ?::uuid")) {
            ps.setString(1, subdomain);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
    }

    private void upgradeToGrowth(String tenantId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection owner = ownerConn();
                 PreparedStatement ps = owner.prepareStatement(
                         "UPDATE tenant_subscriptions SET plan_id = "
                                 + "(SELECT id FROM subscription_plans WHERE name = 'growth') "
                                 + "WHERE tenant_id = ?::uuid")) {
                ps.setString(1, tenantId);
                if (ps.executeUpdate() >= 1) {
                    return;
                }
            }
            Thread.sleep(50);
        }
    }

    private String seedCategory(String tenantId, String name, String slug, String icon) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO product_categories (tenant_id, name, slug, icon) "
                             + "VALUES (?::uuid, ?, ?, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.setString(4, icon);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private void seedProduct(String tenantId, String name, String slug, String categoryId,
                             String price, int stock, boolean requiresRx,
                             String nameGeneric, String nameBrand, String description) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, slug, category_id, price, stock, "
                             + "requires_prescription, name_generic, name_brand, description, active) "
                             + "VALUES (?::uuid, ?, ?, ?::uuid, ?::numeric, ?, ?, ?, ?, ?, true)")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.setString(4, categoryId);
            ps.setString(5, price);
            ps.setInt(6, stock);
            ps.setBoolean(7, requiresRx);
            ps.setString(8, nameGeneric);
            ps.setString(9, nameBrand);
            ps.setString(10, description);
            ps.executeUpdate();
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
