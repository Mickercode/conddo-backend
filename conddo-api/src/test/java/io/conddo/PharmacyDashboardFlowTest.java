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
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Closes HANDOFF_2026-06-07 bugs 1+2 — the pharmacist dashboard's
 * review queues for customer-submitted prescriptions and consultation
 * requests. Wire shape locks in
 * {@code conddo-app/lib/api/pharmacyDashboard.ts}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyDashboardFlowTest {

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
    void emptyQueuesReturn200NotInternalError() throws Exception {
        signup("ph-dash-empty", "owner@ph-dash-empty.test");
        String token = login("ph-dash-empty", "owner@ph-dash-empty.test");

        mockMvc.perform(get("/api/v1/pharmacy/customer-prescriptions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/pharmacy/consultations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void customerPrescriptionsListAndReviewFlow() throws Exception {
        String tenantId = signup("ph-dash-rx", "owner@ph-dash-rx.test");
        String token = login("ph-dash-rx", "owner@ph-dash-rx.test");
        // Seed a pending prescription directly — public submit endpoint is a
        // separate Phase 1 slice (PHARMACY_PUBLIC_API_SPEC §7).
        String rxId = seedPrescription(tenantId, "Doris Ade", "+2348091234567",
                "https://cdn.test/rx-1.jpg", "Doris Ade", "Dr. Adekola");

        // List returns the row with the FE-expected shape.
        MvcResult result = mockMvc.perform(get("/api/v1/pharmacy/customer-prescriptions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(rxId))
                .andExpect(jsonPath("$.data[0].customerName").value("Doris Ade"))
                .andExpect(jsonPath("$.data[0].customerPhone").value("+2348091234567"))
                .andExpect(jsonPath("$.data[0].fileUrl").value("https://cdn.test/rx-1.jpg"))
                .andExpect(jsonPath("$.data[0].patientName").value("Doris Ade"))
                .andExpect(jsonPath("$.data[0].prescriberName").value("Dr. Adekola"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").get(0);
        assertTrue(body.path("submittedAt").asText().length() > 10,
                "submittedAt should be ISO timestamp: " + body);

        // Status filter.
        mockMvc.perform(get("/api/v1/pharmacy/customer-prescriptions?status=PENDING")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Review — approve with a note.
        mockMvc.perform(patch("/api/v1/pharmacy/customer-prescriptions/" + rxId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "APPROVED",
                                "reviewNote", "Valid script, dispense as written."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewNote").value("Valid script, dispense as written."))
                .andExpect(jsonPath("$.data.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.reviewedByName").isNotEmpty());
    }

    @Test
    void consultationsListAndStatusUpdate() throws Exception {
        String tenantId = signup("ph-dash-cons", "owner@ph-dash-cons.test");
        String token = login("ph-dash-cons", "owner@ph-dash-cons.test");
        String cId = seedConsultation(tenantId, "Tunde Ola", "+2348012223344",
                "Refill for hypertension medication", "Weekday afternoons");

        mockMvc.perform(get("/api/v1/pharmacy/consultations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(cId))
                .andExpect(jsonPath("$.data[0].customerName").value("Tunde Ola"))
                .andExpect(jsonPath("$.data[0].whatsappNumber").value("+2348012223344"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        // Transition to confirmed.
        mockMvc.perform(patch("/api/v1/pharmacy/consultations/" + cId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "CONFIRMED",
                                "pharmacistNote", "Will call at 2pm Friday."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.pharmacistNote").value("Will call at 2pm Friday."))
                // completedAt only set on COMPLETED.
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        // Transition to completed.
        mockMvc.perform(patch("/api/v1/pharmacy/consultations/" + cId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "COMPLETED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());
    }

    @Test
    void reviewWithInvalidStatusIs409() throws Exception {
        String tenantId = signup("ph-dash-bad", "owner@ph-dash-bad.test");
        String token = login("ph-dash-bad", "owner@ph-dash-bad.test");
        String rxId = seedPrescription(tenantId, "X", "+1", "https://x", "X", "Dr X");

        mockMvc.perform(patch("/api/v1/pharmacy/customer-prescriptions/" + rxId + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAYBE"))))
                .andExpect(status().isConflict());   // IllegalArgumentException → 409 CONFLICT
    }

    @Test
    void consultationsToolIsInThePharmacyManifest() throws Exception {
        signup("ph-manifest", "owner@ph-manifest.test", "pharmacy");
        String token = login("ph-manifest", "owner@ph-manifest.test");

        // FE calls /manifests?modules=... pulling the active list off the JWT;
        // pass it explicitly here so the registry endpoint resolves the entry.
        MvcResult result = mockMvc.perform(get("/api/v1/registry/manifests?modules=consultations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode manifests = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        boolean found = false;
        for (JsonNode m : manifests) {
            if ("consultations".equals(m.path("toolId").asText())) {
                found = true;
                org.junit.jupiter.api.Assertions.assertEquals("Consultations",
                        m.path("navItem").path("label").asText());
                org.junit.jupiter.api.Assertions.assertEquals("/consultations",
                        m.path("navItem").path("path").asText());
                org.junit.jupiter.api.Assertions.assertEquals("message-circle",
                        m.path("navItem").path("icon").asText());
            }
        }
        assertTrue(found, "Consultations sidebar entry must appear for pharmacy tenants: " + manifests);
    }

    // ----- helpers -----------------------------------------------------------

    private String signup(String slug, String adminEmail) throws Exception {
        return signup(slug, adminEmail, null);
    }

    private String signup(String slug, String adminEmail, String verticalId) throws Exception {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", slug + " Business");
        body.put("slug", slug);
        body.put("adminEmail", adminEmail);
        body.put("adminPassword", PASSWORD);
        if (verticalId != null) {
            body.put("verticalId", verticalId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
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

    private String seedPrescription(String tenantId, String customerName, String phone,
                                    String fileUrl, String patient, String prescriber) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO customer_prescriptions (tenant_id, customer_name, customer_phone, "
                             + "file_url, patient_name, prescriber_name, status) "
                             + "VALUES (?::uuid, ?, ?, ?, ?, ?, 'PENDING') RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, customerName);
            ps.setString(3, phone);
            ps.setString(4, fileUrl);
            ps.setString(5, patient);
            ps.setString(6, prescriber);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String seedConsultation(String tenantId, String customerName, String whatsapp,
                                    String topic, String preferred) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO consultations (tenant_id, customer_name, whatsapp_number, "
                             + "topic, preferred_time, status) "
                             + "VALUES (?::uuid, ?, ?, ?, ?, 'PENDING') RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, customerName);
            ps.setString(3, whatsapp);
            ps.setString(4, topic);
            ps.setString(5, preferred);
            try (var rs = ps.executeQuery()) {
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
