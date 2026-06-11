package io.conddo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.paystack.PaystackGateway;
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

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Roadmap Beta 3 — Drug Programs end-to-end.
 *
 * <ul>
 *   <li>Create + publish + list (with enrollments count).</li>
 *   <li>Manual enrol fires Paystack init and persists a PENDING_PAYMENT row.</li>
 *   <li>charge.success webhook activates the enrollment.</li>
 *   <li>Public listing shows only published programs.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyProgramFlowTest {

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
        registry.add("conddo.pharmacy.reminder-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.discount-expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.followup-missed-cron", () -> "0 0 0 1 1 ?");
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
    @MockBean
    private PaystackGateway paystackGateway;

    @Test
    void featureGateRefusesWithoutFlag() throws Exception {
        signup("prg-gate", "owner@prg-gate.test");
        String token = login("prg-gate", "owner@prg-gate.test");
        mockMvc.perform(get("/api/v1/pharmacy/programs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FEATURE_NOT_ENABLED"))
                .andExpect(jsonPath("$.error.details[0].message").value("drug_programs"));
    }

    @Test
    void fullCreatePublishEnrolAndWebhookActivate() throws Exception {
        when(paystackGateway.initialize(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> new PaystackGateway.InitResult(
                        "https://checkout.paystack.com/p",
                        invocation.getArgument(2), null));
        when(paystackGateway.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);

        String tenantId = signup("prg-flow", "owner@prg-flow.test");
        String token = login("prg-flow", "owner@prg-flow.test");
        grantFeature(tenantId, "drug_programs");
        String pid = seedProduct(tenantId, "Metformin");
        String customerId = seedCustomer(tenantId, "Diabetic Buyer", "patient@flow.test");

        // Create a draft program.
        MvcResult createdRes = mockMvc.perform(post("/api/v1/pharmacy/programs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Diabetes Care",
                                "description", "Monthly Metformin + reminders",
                                "targetCondition", "Type 2 Diabetes",
                                "durationMonths", 12,
                                "monthlyPrice", 15000,
                                "items", List.of(Map.of(
                                        "productId", pid, "quantity", 1, "frequency", "MONTHLY"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Diabetes Care"))
                .andExpect(jsonPath("$.data.isPublished").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        String programId = objectMapper.readTree(createdRes.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Publish.
        mockMvc.perform(patch("/api/v1/pharmacy/programs/" + programId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("isPublished", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPublished").value(true));

        // Manual enrol — returns hosted URL + reference, enrollment is PENDING_PAYMENT.
        MvcResult enrolRes = mockMvc.perform(post("/api/v1/pharmacy/programs/" + programId + "/enroll")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("customerId", customerId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value("https://checkout.paystack.com/p"))
                .andExpect(jsonPath("$.data.enrollment.status").value("PENDING_PAYMENT"))
                .andReturn();
        JsonNode body = objectMapper.readTree(enrolRes.getResponse().getContentAsString());
        String reference = body.path("data").path("reference").asText();
        String enrollmentId = body.path("data").path("enrollment").path("id").asText();
        assertTrue(reference.startsWith("CONDDO_PROG_"), "ref should be CONDDO_PROG_ prefixed: " + reference);

        // Webhook fires charge.success → enrollment flips to ACTIVE.
        String payload = objectMapper.writeValueAsString(Map.of(
                "event", "charge.success",
                "data", Map.of(
                        "reference", reference,
                        "paid_at", OffsetDateTime.now().toString(),
                        "authorization", Map.of("authorization_code", "SUB_PROG_TEST"))));
        mockMvc.perform(post("/api/v1/billing/webhooks/paystack")
                        .header("x-paystack-signature", "ok-sig-mocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        assertEquals("ACTIVE", readEnrollmentStatus(enrollmentId));

        // Enrollments list shows the new ACTIVE row.
        mockMvc.perform(get("/api/v1/pharmacy/programs/" + programId + "/enrollments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        // Programs list now shows enrollmentsCount=1.
        mockMvc.perform(get("/api/v1/pharmacy/programs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].enrollmentsCount").value(1));
    }

    @Test
    void publicEndpointOnlyListsPublishedPrograms() throws Exception {
        String tenantId = signup("prg-pub", "owner@prg-pub.test");
        String token = login("prg-pub", "owner@prg-pub.test");
        grantFeature(tenantId, "drug_programs");
        String apiKey = regenerateKey(token);
        activateSite(tenantId, "prg-pub");
        String pid = seedProduct(tenantId, "Amlodipine");

        // Create + publish one program; create a draft without publishing.
        String publishedId = createProgram(token, "Hypertension Care", pid, true);
        createProgram(token, "Heart Care Draft", pid, false);

        MvcResult res = mockMvc.perform(get("/api/v1/public/prg-pub/pharmacy/programs")
                        .header("X-Conddo-Site-Key", apiKey))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode programs = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("programs");
        assertEquals(1, programs.size(), "only published programs surface");
        assertEquals(publishedId, programs.get(0).path("id").asText());
    }

    // ----- helpers ----------------------------------------------------------

    private String createProgram(String token, String name, String pid, boolean publish) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/pharmacy/programs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "monthlyPrice", new BigDecimal("10000"),
                                "items", List.of(Map.of(
                                        "productId", pid, "quantity", 1, "frequency", "MONTHLY"))))))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asText();
        if (publish) {
            mockMvc.perform(patch("/api/v1/pharmacy/programs/" + id + "/publish")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isPublished", true))))
                    .andExpect(status().isOk());
        }
        return id;
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

    private String seedCustomer(String tenantId, String name, String email) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO customers (tenant_id, full_name, email, phone) "
                             + "VALUES (?::uuid, ?, ?, '+2348091230000') RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, email);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String seedProduct(String tenantId, String name) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO products (tenant_id, name, sku, price, stock, "
                             + "reorder_threshold, active, name_generic) "
                             + "VALUES (?::uuid, ?, ?, 1000, 100, 0, true, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toUpperCase() + "-SKU");
            ps.setString(4, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String readEnrollmentStatus(String id) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status FROM pharmacy_program_enrollments WHERE id = ?::uuid")) {
            ps.setString(1, id);
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
