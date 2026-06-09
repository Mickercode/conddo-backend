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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Module Spec v2 §12E — Refill Offers.
 * <ul>
 *   <li>Pharmacist creates an offer + issues it to a specific customer
 *       (claim row + REFILL-XXXX code).</li>
 *   <li>Public validate endpoint returns valid=true for the right
 *       customer, valid=false with reason for everything else.</li>
 *   <li>Customer checks out with the code — the matching line is
 *       discounted, the claim is marked used + carries order_id.</li>
 *   <li>Re-using the code → claim already redeemed → no second
 *       discount (treated as a stale code, order still completes).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyRefillOfferFlowTest {

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
    void issueValidateAndCheckoutAppliesDiscountAndRedeems() throws Exception {
        String tenantId = signup("ref-flow", "owner@ref-flow.test");
        String adminToken = login("ref-flow", "owner@ref-flow.test");
        String apiKey = regenerateKey(adminToken);
        activateSite(tenantId, "ref-flow");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "Amlodipine", "1000.00", "amlodipine");

        // Customer registers + address.
        String custToken = registerCustomer(apiKey, "ref-flow", "buyer@ref-flow.test", "Buyer One");
        String addressId = createAddress(apiKey, "ref-flow", custToken, "Lagos");
        String customerId = readCustomerId(tenantId, "buyer@ref-flow.test");

        // Pharmacist creates an offer (10% off, 30 days, max 1 use).
        MvcResult offerRes = mockMvc.perform(post("/api/v1/pharmacy/refill-offers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "discountType", "PERCENTAGE",
                                "discountValue", 10,
                                "validDays", 30,
                                "maxUses", 1,
                                "message", "Hi {firstName}, refill {productName} within 30 days for 10% off."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.offer.discountValue").value(10))
                .andReturn();
        String offerId = objectMapper.readTree(offerRes.getResponse().getContentAsString())
                .path("data").path("offer").path("id").asText();

        // Pharmacist issues the offer to the customer.
        MvcResult issueRes = mockMvc.perform(post("/api/v1/pharmacy/refill-offers/" + offerId + "/issue")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "sendSms", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.claim.offerCode").exists())
                .andExpect(jsonPath("$.data.claim.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.smsRequested").value(true))
                .andReturn();
        String offerCode = objectMapper.readTree(issueRes.getResponse().getContentAsString())
                .path("data").path("claim").path("offerCode").asText();
        assertTrue(offerCode.startsWith("REFILL-"), "code should be REFILL-prefixed: " + offerCode);

        // Public validate as the customer → valid=true with offer details.
        mockMvc.perform(get("/api/v1/public/ref-flow/pharmacy/refill-offer/" + offerCode)
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.offer.discountedPrice").value(900.00))
                .andExpect(jsonPath("$.offer.expiresAt").isNotEmpty());

        // Public validate as a DIFFERENT customer → WRONG_CUSTOMER.
        String otherToken = registerCustomer(apiKey, "ref-flow", "other@ref-flow.test", "Other");
        mockMvc.perform(get("/api/v1/public/ref-flow/pharmacy/refill-offer/" + offerCode)
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("WRONG_CUSTOMER"));

        // Public validate with garbage code → NOT_FOUND.
        mockMvc.perform(get("/api/v1/public/ref-flow/pharmacy/refill-offer/REFILL-XXXX")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"));

        // Checkout with the code — 1 × 900 (offer) = 900 + 1500 delivery = 2400 total.
        mockMvc.perform(post("/api/v1/public/ref-flow/pharmacy/orders")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId,
                                "refillOfferCode", offerCode))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.subtotal").value(900.00))
                .andExpect(jsonPath("$.order.deliveryFee").value(1500))
                .andExpect(jsonPath("$.order.total").value(2400.00));

        // Validate after redemption → USED.
        mockMvc.perform(get("/api/v1/public/ref-flow/pharmacy/refill-offer/" + offerCode)
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("USED"));

        // Claim row carries used_at + order_id.
        assertEquals(true, readClaimUsed(offerCode), "claim should be marked used");
    }

    @Test
    void checkoutWithoutCodeUsesListPrice() throws Exception {
        String tenantId = signup("ref-skip", "owner@ref-skip.test");
        String adminToken = login("ref-skip", "owner@ref-skip.test");
        String apiKey = regenerateKey(adminToken);
        activateSite(tenantId, "ref-skip");
        upgradeToGrowth(tenantId);
        String pid = seedProduct(tenantId, "Amlodipine", "1000.00", "amlodipine-skip");

        String custToken = registerCustomer(apiKey, "ref-skip", "buyer@ref-skip.test", "Buyer Skip");
        String addressId = createAddress(apiKey, "ref-skip", custToken, "Lagos");

        // Order WITHOUT any refill code — full list price applies.
        mockMvc.perform(post("/api/v1/public/ref-skip/pharmacy/orders")
                        .header("X-Conddo-Site-Key", apiKey)
                        .header(HttpHeaders.AUTHORIZATION, bearer(custToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                                "addressId", addressId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.subtotal").value(1000.00));
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

    private String readCustomerId(String tenantId, String email) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT id FROM customers WHERE tenant_id = ?::uuid AND email = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "customer must exist");
                return rs.getString(1);
            }
        }
    }

    private boolean readClaimUsed(String code) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT used_at, order_id FROM pharmacy_refill_offer_claims WHERE offer_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "claim must exist");
                return rs.getObject("used_at") != null && rs.getObject("order_id") != null;
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
