package io.conddo.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Site Registration admin — ops can register tenant sites, rotate keys,
 * QA-approve, edit metadata, and read the audit log without raw SQL
 * (SITE_REGISTRATION_ADMIN_SPEC §8 — tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PlatformSiteAdminFlowTest {

    private static final String PW = "studio-pass-123";
    private static final String ADMIN = "siteadmin@studio.test";
    private static final String LEAD = "sitelead@studio.test";
    private static final String QA = "siteqa@studio.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo").withUsername("conddo_owner").withPassword("owner_password")
            .withInitScript("test-platform-tables.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("studio.jwt.secret", () -> "test-studio-secret-at-least-32-bytes-long-0123456789");
        registry.add("studio.cors.allowed-origins", () -> "http://localhost:3000");
        registry.add("studio.service.token", () -> "test-service-token");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    /** Seed staff (ADMIN, TEAM_LEAD, QA_REVIEWER) once per test. Idempotent. */
    @BeforeEach
    void seedStaff() throws SQLException {
        String hash = new BCryptPasswordEncoder(12).encode(PW);
        try (Connection c = ownerConn()) {
            insertStaff(c, ADMIN, hash, "Site Admin", "ADMIN");
            insertStaff(c, LEAD, hash, "Site Lead", "TEAM_LEAD");
            insertStaff(c, QA, hash, "Site QA", "QA_REVIEWER");
        }
    }

    // ----- registration ------------------------------------------------------

    @Test
    void adminRegistersSiteWithPlaintextKeyOnceAndRowReadsMasked() throws Exception {
        UUID tenantId = seedTenant("Seb & Bayor Pharmaceutical", "seb-bayor", "pharmacy");
        String adminToken = login(ADMIN);

        MvcResult created = mockMvc.perform(post("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId,
                                "subdomain", "seb-bayorpharmaceutical",
                                "customDomain", "sebandbayor.com.ng",
                                "hostingProvider", "9stacks",
                                "siteType", "custom_built"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.apiKey").exists())
                .andExpect(jsonPath("$.data.site.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.data.site.tenantName").value("Seb & Bayor Pharmaceutical"))
                .andExpect(jsonPath("$.data.site.subdomain").value("seb-bayorpharmaceutical"))
                .andExpect(jsonPath("$.data.site.customDomain").value("sebandbayor.com.ng"))
                .andExpect(jsonPath("$.data.site.isActive").value(false))
                .andExpect(jsonPath("$.data.site.qaApproved").value(false))
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        String plaintext = body.path("apiKey").asText();
        String siteId = body.path("site").path("id").asText();
        String last4 = plaintext.substring(plaintext.length() - 4);
        assertTrue(plaintext.startsWith("sk_live_"), "plaintext must be sk_live_-prefixed: " + plaintext);
        assertEquals(last4, body.path("site").path("apiKeyLast4").asText());

        // Subsequent GET shows the masked key, NOT the plaintext.
        mockMvc.perform(get("/api/jobs/admin/platform/sites/" + siteId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMasked").value("sk_live_••••••••" + last4))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        // bcrypt round-trip: the stored hash verifies against the plaintext.
        String storedHash = readApiKeyHash(siteId);
        assertTrue(new BCryptPasswordEncoder(12).matches(plaintext, storedHash),
                "bcrypt hash must verify against the plaintext returned to the FE");

        // The registration is reflected in the cross-tenant list. Other tests
        // in this class share the Postgres container, so the row count is
        // ≥1; locate our row by subdomain rather than pinning the count.
        MvcResult listResult = mockMvc.perform(get("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(listResult.getResponse().getContentAsString()).path("data");
        boolean foundSebBayor = false;
        for (JsonNode row : rows) {
            if ("seb-bayorpharmaceutical".equals(row.path("subdomain").asText())) {
                foundSebBayor = true;
            }
        }
        assertTrue(foundSebBayor, "expected Seb&Bayor row in the cross-tenant list: " + rows);

        // The audit log has the REGISTERED entry, attributed to the admin.
        mockMvc.perform(get("/api/jobs/admin/platform/sites/" + siteId + "/audit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].action").value("REGISTERED"))
                .andExpect(jsonPath("$.data[0].byStaffName").value("Site Admin"));
    }

    @Test
    void registeringTwiceForTheSameTenantIs409() throws Exception {
        UUID tenantId = seedTenant("Dup Co", "dup", "fashion");
        String adminToken = login(ADMIN);
        registerSite(adminToken, tenantId, "dup-co", null);

        mockMvc.perform(post("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId,
                                "subdomain", "different-subdomain"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TENANT_ALREADY_HAS_SITE"));
    }

    @Test
    void registeringWithATakenSubdomainIs409() throws Exception {
        UUID t1 = seedTenant("Tenant One", "t-one", "fashion");
        UUID t2 = seedTenant("Tenant Two", "t-two", "fashion");
        String adminToken = login(ADMIN);
        registerSite(adminToken, t1, "shared-slug", null);

        mockMvc.perform(post("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", t2,
                                "subdomain", "shared-slug"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBDOMAIN_TAKEN"));
    }

    // ----- rotate-key --------------------------------------------------------

    @Test
    void rotateKeyIssuesNewPlaintextAndInvalidatesOldHash() throws Exception {
        UUID tenantId = seedTenant("Rotate Co", "rotate", "fashion");
        String adminToken = login(ADMIN);
        String siteId = registerSite(adminToken, tenantId, "rotate-co", null);
        String hashBefore = readApiKeyHash(siteId);

        MvcResult rotated = mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/rotate-key")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").exists())
                .andReturn();
        String newPlaintext = objectMapper.readTree(rotated.getResponse().getContentAsString())
                .path("data").path("apiKey").asText();
        assertTrue(newPlaintext.startsWith("sk_live_"));
        String newHash = readApiKeyHash(siteId);
        assertNotEquals(hashBefore, newHash, "rotation must produce a new bcrypt hash");
        assertTrue(new BCryptPasswordEncoder(12).matches(newPlaintext, newHash));

        // Audit log now has 2 entries: REGISTERED, then KEY_ROTATED (most recent first).
        mockMvc.perform(get("/api/jobs/admin/platform/sites/" + siteId + "/audit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].action").value("KEY_ROTATED"));
    }

    // ----- QA approve / revoke ----------------------------------------------

    @Test
    void qaReviewerCanApproveAndRevoke() throws Exception {
        UUID tenantId = seedTenant("Approve Co", "approve", "fashion");
        String adminToken = login(ADMIN);
        String siteId = registerSite(adminToken, tenantId, "approve-co", null);

        String qaToken = login(QA);
        mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/qa-approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "Live URL matches the brief; mobile OK."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qaApproved").value(true))
                .andExpect(jsonPath("$.data.qaApprovedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.qaApprovedByName").value("Site QA"));

        mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/qa-revoke")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "Customer reported broken cart — rolling back."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qaApproved").value(false));
    }

    @Test
    void qaReviewerCannotRotateKey() throws Exception {
        UUID tenantId = seedTenant("Forbidden Co", "forbid", "fashion");
        String adminToken = login(ADMIN);
        String siteId = registerSite(adminToken, tenantId, "forbid-co", null);

        String qaToken = login(QA);
        mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/rotate-key")
                        .header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isForbidden());
    }

    // ----- activation --------------------------------------------------------

    @Test
    void adminCanActivateAndDeactivate() throws Exception {
        UUID tenantId = seedTenant("Toggle Co", "toggle", "fashion");
        String adminToken = login(ADMIN);
        String siteId = registerSite(adminToken, tenantId, "toggle-co", null);

        mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true));

        mockMvc.perform(post("/api/jobs/admin/platform/sites/" + siteId + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    // ----- PATCH metadata ----------------------------------------------------

    @Test
    void patchUpdatesMetadataAndWritesAuditDiff() throws Exception {
        UUID tenantId = seedTenant("Patch Co", "patch", "fashion");
        String adminToken = login(ADMIN);
        String siteId = registerSite(adminToken, tenantId, "patch-co", null);

        mockMvc.perform(patch("/api/jobs/admin/platform/sites/" + siteId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hostingProvider", "vercel",
                                "submittedUrl", "https://staging.patch-co.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostingProvider").value("vercel"))
                .andExpect(jsonPath("$.data.submittedUrl").value("https://staging.patch-co.test"));

        mockMvc.perform(get("/api/jobs/admin/platform/sites/" + siteId + "/audit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("METADATA_UPDATED"));
    }

    // ----- RBAC --------------------------------------------------------------

    @Test
    void teamLeadCanReadButNotMutate() throws Exception {
        UUID tenantId = seedTenant("Lead Co", "lead", "fashion");
        String adminToken = login(ADMIN);
        registerSite(adminToken, tenantId, "lead-co", null);

        String leadToken = login(LEAD);
        // Read OK.
        mockMvc.perform(get("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(leadToken)))
                .andExpect(status().isOk());

        // Register denied.
        mockMvc.perform(post("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(leadToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId, "subdomain", "lead-attempt"))))
                .andExpect(status().isForbidden());
    }

    // ----- helpers -----------------------------------------------------------

    private String registerSite(String adminToken, UUID tenantId, String subdomain,
                                String customDomain) throws Exception {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("subdomain", subdomain);
        body.put("customDomain", customDomain);
        MvcResult result = mockMvc.perform(post("/api/jobs/admin/platform/sites")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("site").path("id").asText();
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PW))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private UUID seedTenant(String name, String slug, String vertical) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ownerConn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO public.tenants (id, name, slug, vertical_id, status) "
                             + "VALUES (?, ?, ?, ?, 'ACTIVE')")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.setString(4, vertical);
            ps.executeUpdate();
        }
        return id;
    }

    private String readApiKeyHash(String siteId) throws SQLException {
        try (Connection c = ownerConn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT api_key_hash FROM public.tenant_sites WHERE id = ?::uuid")) {
            ps.setString(1, siteId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static void insertStaff(Connection c, String email, String hash, String name,
                                    String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO studio.staff (email, password_hash, full_name, role, skills) "
                        + "VALUES (?, ?, ?, ?, '[]'::jsonb) ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, email);
            ps.setString(2, hash);
            ps.setString(3, name);
            ps.setString(4, role);
            ps.executeUpdate();
        }
    }

    private Connection ownerConn() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
