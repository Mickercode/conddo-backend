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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Module Spec v2 §12B — Discount System.
 * <ul>
 *   <li>Create lands in PENDING_APPROVAL.</li>
 *   <li>Non-admin can't approve (403); admin can.</li>
 *   <li>Approved discount surfaces in the public catalog GET as
 *       {@code discountedPrice} / {@code discountLabel} /
 *       {@code discountEndsAt}.</li>
 *   <li>Public order checkout applies the discount — total reflects
 *       discounted unit price, not list price.</li>
 *   <li>Reject with note sets REJECTED + carries the note back.</li>
 *   <li>Delete: creator can drop their own pending, admin can drop
 *       anything.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyDiscountFlowTest {

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
    void createApproveAndPublicCatalogShowsDiscountedPrice() throws Exception {
        String tenantId = signup("disc-flow", "owner@disc-flow.test");
        String adminToken = login("disc-flow", "owner@disc-flow.test");
        String apiKey = regenerateKey(adminToken);
        activateSite(tenantId, "disc-flow");
        String pid = seedProduct(tenantId, "Vitamin C", "2000.00", "vitamin-c");

        // Create discount — PENDING_APPROVAL.
        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/discounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "discountType", "PERCENTAGE",
                                "discountValue", 20,
                                "label", "20% OFF — June Promo",
                                "startsAt", OffsetDateTime.now().minusHours(1).toString(),
                                "endsAt", OffsetDateTime.now().plusDays(7).toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.discount.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.discount.discountedPrice").value(1600.00))
                .andReturn();
        String discountId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("discount").path("id").asText();

        // Catalog GET — pending discount does NOT appear (only APPROVED).
        mockMvc.perform(get("/api/v1/public/disc-flow/pharmacy/products")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].discountedPrice").doesNotExist())
                .andExpect(jsonPath("$.products[0].price").value(2000));

        // Admin approves.
        mockMvc.perform(patch("/api/v1/pharmacy/discounts/" + discountId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("action", "APPROVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discount.status").value("APPROVED"));

        // Now the catalog GET shows the discount fields.
        mockMvc.perform(get("/api/v1/public/disc-flow/pharmacy/products")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].discountedPrice").value(1600.00))
                .andExpect(jsonPath("$.products[0].discountPercent").value(20))
                .andExpect(jsonPath("$.products[0].discountLabel").value("20% OFF — June Promo"))
                .andExpect(jsonPath("$.products[0].discountEndsAt").isNotEmpty());
    }

    @Test
    void rejectionCarriesNoteBack() throws Exception {
        String tenantId = signup("disc-reject", "owner@disc-reject.test");
        String adminToken = login("disc-reject", "owner@disc-reject.test");
        String pid = seedProduct(tenantId, "Paracetamol", "300.00", "paracetamol");

        String discountId = createDiscount(adminToken, pid, "PERCENTAGE", 50, "Cheap");
        mockMvc.perform(patch("/api/v1/pharmacy/discounts/" + discountId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "REJECT",
                                "note", "Price too low — minimum 10% margin required"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discount.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.discount.rejectionNote")
                        .value("Price too low — minimum 10% margin required"));
    }

    @Test
    void publicCheckoutAppliesDiscountToOrderTotal() throws Exception {
        String tenantId = signup("disc-buy", "owner@disc-buy.test");
        String adminToken = login("disc-buy", "owner@disc-buy.test");
        String apiKey = regenerateKey(adminToken);
        activateSite(tenantId, "disc-buy");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "Paracetamol", "300.00", "paracetamol-buy");

        // Create + admin-approve a 20% discount.
        String discountId = createDiscount(adminToken, pid, "PERCENTAGE", 20, "20% OFF");
        mockMvc.perform(patch("/api/v1/pharmacy/discounts/" + discountId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("action", "APPROVE"))))
                .andExpect(status().isOk());

        // Customer registers + addresses + buys.
        String custToken = registerCustomer(apiKey, "disc-buy", "buyer@disc-buy.test", "Buyer One");
        String addressId = createAddress(apiKey, "disc-buy", custToken, "Lagos");

        // 2 × 300 list = 600; 20% off → 240 unit; total = 480 + 1500 delivery = 1980.
        MvcResult result = mockMvc.perform(post("/api/v1/public/disc-buy/pharmacy/orders")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 2)),
                                "addressId", addressId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.subtotal").value(480.00))
                .andExpect(jsonPath("$.order.deliveryFee").value(1500))
                .andExpect(jsonPath("$.order.total").value(1980.00))
                .andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("\"success\":true"));
    }

    /**
     * Spec v2 §12B implementation note: "Conddo notifies the Tenant
     * Admin via dashboard notification that a discount is awaiting
     * approval." Listener runs @Async AFTER_COMMIT — poll the bell
     * feed briefly until the row lands.
     */
    @Test
    void creatingDiscountFiresBellFeedNudgeToTenantAdmin() throws Exception {
        String tenantId = signup("disc-bell", "owner@disc-bell.test");
        String adminToken = login("disc-bell", "owner@disc-bell.test");
        String pid = seedProduct(tenantId, "Aspirin", "200.00", "aspirin-bell");

        createDiscount(adminToken, pid, "PERCENTAGE", 15, "Quick promo");

        long deadline = System.currentTimeMillis() + 5_000;
        boolean found = false;
        while (System.currentTimeMillis() < deadline && !found) {
            MvcResult feed = mockMvc.perform(get("/api/v1/notifications")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode items = objectMapper.readTree(feed.getResponse().getContentAsString())
                    .path("data").path("items");
            for (JsonNode item : items) {
                if ("DISCOUNT_PENDING".equals(item.path("type").asText())) {
                    assertTrue(item.path("title").asText().contains("pending approval"),
                            "title should mention approval: " + item);
                    assertTrue(item.path("body").asText().contains("Aspirin"),
                            "body should name the product: " + item);
                    found = true;
                    break;
                }
            }
            if (!found) {
                Thread.sleep(50);
            }
        }
        assertTrue(found, "DISCOUNT_PENDING bell-feed row never landed within 5s");
    }

    @Test
    void deletePendingByCreatorWorks() throws Exception {
        String tenantId = signup("disc-del", "owner@disc-del.test");
        String adminToken = login("disc-del", "owner@disc-del.test");
        String pid = seedProduct(tenantId, "Aspirin", "100.00", "aspirin");
        String discountId = createDiscount(adminToken, pid, "FIXED", 10, "Quick 10");

        // Owner is the creator AND is TENANT_ADMIN — delete works either way.
        mockMvc.perform(delete("/api/v1/pharmacy/discounts/" + discountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(get("/api/v1/pharmacy/discounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ----- helpers ----------------------------------------------------------

    private String createDiscount(String token, String pid, String type, int value, String label) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/pharmacy/discounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "discountType", type,
                                "discountValue", value,
                                "label", label,
                                "startsAt", OffsetDateTime.now().minusHours(1).toString(),
                                "endsAt", OffsetDateTime.now().plusDays(7).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("discount").path("id").asText();
    }

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
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
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
        throw new IllegalStateException("trial subscription never landed");
    }

    private String registerCustomer(String apiKey, String slug, String email, String fullName) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slug + "/auth/register")
                        .header("X-Conddo-Site-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", fullName,
                                "email", email,
                                "password", "buyerpw123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("token").asText();
    }

    private String createAddress(String apiKey, String slug, String custToken, String state) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/public/" + slug + "/customer/addresses")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "label", "Home",
                                "street", "12 Allen Ave",
                                "city", "Ikeja",
                                "state", state,
                                "isDefault", true))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("id").asText();
    }

    private String seedProduct(String tenantId, String name, String price, String slug) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active, slug, name_generic) "
                             + "VALUES (?::uuid, ?, ?, ?::numeric, 100, 0, true, ?, ?) "
                             + "RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toUpperCase().replace(' ', '-') + "-SKU");
            ps.setString(4, price);
            ps.setString(5, slug);
            ps.setString(6, name);
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
