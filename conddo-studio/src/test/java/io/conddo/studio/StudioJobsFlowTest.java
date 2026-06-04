package io.conddo.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.ai.ClaudeClient;
import io.conddo.studio.auth.StudioServiceTokenFilter;
import io.conddo.studio.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the Studio Jobs Board on a fully-booted service
 * (Testcontainers Postgres, Flyway studio/jobs schemas, the HMAC STUDIO_JWT).
 * Walks the full job state machine claim→start→submit→QA-return→resubmit→approve
 * across developer / QA / admin roles, plus auth, notifications, performance, RBAC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StudioJobsFlowTest {

    private static final String PW = "studio-pass-123";
    private static final String ADMIN = "admin@studio.test";
    private static final String DEV = "dev@studio.test";
    private static final String QA = "qa@studio.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conddo").withUsername("conddo_owner").withPassword("owner_password")
            // Seed the public.tenants/users tables conddo-api normally owns, so
            // Studio's @Table(schema=public) mirror entities pass ddl-auto:validate
            // at boot (§23.2 read-only mirror).
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

    /**
     * Stub Claude so the AI endpoints run end-to-end without a real API key. Returns
     * one superset JSON that satisfies copy (headline), palette (primary), and QA
     * scan (overallQuality). {@code @Primary} overrides the real (dormant) client.
     */
    @TestConfiguration
    static class AiTestConfig {
        @Bean
        @Primary
        ClaudeClient stubClaudeClient() {
            return new ClaudeClient() {
                @Override
                public Optional<String> complete(String system, String user, int maxTokens, boolean think) {
                    return Optional.of("{\"headline\":\"Genuine medicines, always in stock\","
                            + "\"subheadline\":\"Your trusted Lekki pharmacy\",\"ctaText\":\"Order now\","
                            + "\"primary\":\"#7C5CBF\",\"background\":\"#FFFFFF\","
                            + "\"issues\":[],\"positives\":[\"Clean layout\"],\"overallQuality\":\"PASS\","
                            + "\"score\":9,\"reason\":\"Bright, clear product shot\",\"recommendation\":\"RECOMMENDED\"}");
                }

                @Override
                public boolean isConfigured() {
                    return true;
                }
            };
        }
    }

    /**
     * In-memory object storage so the asset endpoints can be exercised end-to-end
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
                        throw new io.conddo.studio.storage.StorageException("read failed", e);
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

    /** Seed three staff (the service has no public staff-creation bootstrap). Idempotent. */
    @BeforeEach
    void seedStaff() throws SQLException {
        String hash = new BCryptPasswordEncoder(12).encode(PW);
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            insertStaff(c, ADMIN, hash, "Ada Admin", "ADMIN", "[]");
            insertStaff(c, DEV, hash, "Dele Dev", "DEVELOPER", "[\"WEBSITE_BUILD\"]");
            insertStaff(c, QA, hash, "Queen QA", "QA_REVIEWER", "[]");
        }
    }

    @Test
    void fullJobLifecycleAcrossRoles() throws Exception {
        String adminToken = login(ADMIN);

        // Admin creates a website-build job — lands AVAILABLE with an SLA tone.
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Website Build — Glam by Adaeze",
                                "brief", Map.of("businessName", "Glam by Adaeze")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.jobNumber").value(org.hamcrest.Matchers.startsWith("WB-")))
                .andExpect(jsonPath("$.data.slaTone").exists())
                .andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Developer sees it in available (skills include WEBSITE_BUILD), claims, starts, submits.
        String devToken = login(DEV);
        // Other tests in this class may have left WEBSITE_BUILD jobs behind that DEV can also see
        // — assert at least one (their own) is visible rather than exactly one.
        mockMvc.perform(get("/api/jobs/available").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));
        mockMvc.perform(patch("/api/jobs/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/jobs/" + jobId + "/submit").header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("studioUrl", "https://studio/x"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        // QA picks it up and returns it for revision.
        String qaToken = login(QA);
        // Tolerant of pollution from other tests that submit jobs (websiteBuilder, etc.).
        mockMvc.perform(get("/api/jobs/qa/queue").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_REVIEW"));
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/return").header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("feedback", "Logo is too small"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVISION"))
                .andExpect(jsonPath("$.data.revisionCount").value(1));

        // The developer is notified of the revision.
        mockMvc.perform(get("/api/jobs/notifications").param("unread", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("QA_REVISION"));

        // Bulk-mark all as read (notifications drawer "mark all read") drops the unread count.
        mockMvc.perform(patch("/api/jobs/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(1));
        mockMvc.perform(get("/api/jobs/notifications").param("unread", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(0));

        // Developer fixes + resubmits; QA approves.
        mockMvc.perform(patch("/api/jobs/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/" + jobId + "/submit").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/approve").header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // Admin dashboard reflects the approved job.
        mockMvc.perform(get("/api/jobs/admin/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byStatus.APPROVED").value(1));

        // Auth /me + performance for the developer.
        mockMvc.perform(get("/api/jobs/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"));
        mockMvc.perform(get("/api/jobs/performance/me").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobsCompleted").value(1));

        // RBAC: a developer cannot reach the QA queue or admin board.
        mockMvc.perform(get("/api/jobs/qa/queue").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/jobs/admin/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenIntakeCreatesAJobAndRejectsBadTokens() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "jobTypeId", "WEBSITE_REVISION",
                "tenantId", java.util.UUID.randomUUID().toString(),
                "title", "Website edit — Glam by Adaeze",
                "brief", Map.of("source", "website-change-request", "details", "Make the logo bigger")));

        // Valid service token (the platform's hand-off) -> a job lands AVAILABLE.
        mockMvc.perform(post("/api/jobs/intake")
                        .header(StudioServiceTokenFilter.HEADER, "test-service-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.jobNumber").value(org.hamcrest.Matchers.startsWith("WR-")));

        // No token and a wrong token both fail closed (401), never creating a job.
        mockMvc.perform(post("/api/jobs/intake").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/jobs/intake")
                        .header(StudioServiceTokenFilter.HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aiAssistantGeneratesCopyPaletteAndScan() throws Exception {
        // Admin creates a website-build job with a pharmacy brief.
        String adminToken = login(ADMIN);
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Website Build — MedPlus",
                                "brief", Map.of("businessName", "MedPlus", "vertical", "pharmacy",
                                        "description", "Community pharmacy in Lekki")))))
                .andExpect(status().isCreated()).andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Dev claims it (→ ASSIGNED) so it doesn't pollute the lifecycle test's available count.
        String devToken = login(DEV);
        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());

        // Dev requests AI copy for the HERO section — returned + stored on the job.
        mockMvc.perform(post("/api/jobs/" + jobId + "/ai-suggest").header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("section", "HERO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.section").value("HERO"))
                .andExpect(jsonPath("$.data.copy.headline").value("Genuine medicines, always in stock"));

        mockMvc.perform(get("/api/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiSuggestions.HERO.headline")
                        .value("Genuine medicines, always in stock"));

        // Palette utility — no job required.
        mockMvc.perform(post("/api/jobs/ai/palette").header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("primaryHex", "#7C5CBF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.palette.primary").value("#7C5CBF"));

        // QA scan is QA-only.
        String qaToken = login(QA);
        mockMvc.perform(get("/api/jobs/qa/" + jobId + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.scan.overallQuality").value("PASS"));

        mockMvc.perform(get("/api/jobs/qa/" + jobId + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden());

        // Image ranker — vertical pulled from the job's brief, returns sorted ranked list.
        mockMvc.perform(post("/api/jobs/" + jobId + "/rank-images").header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageUrls", java.util.List.of("https://stock/x.png", "https://stock/y.png"),
                                "sectionType", "hero"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.ranked.length()").value(2))
                .andExpect(jsonPath("$.data.ranked[0].score").value(9))
                .andExpect(jsonPath("$.data.ranked[0].recommendation").value("RECOMMENDED"));
    }

    @Test
    void jobAssetsUploadListAndDelete() throws Exception {
        // Admin creates a website-build job; dev claims so it doesn't pollute the lifecycle test.
        String adminToken = login(ADMIN);
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Website Build — Assets test",
                                "brief", Map.of("businessName", "AssetsCo", "vertical", "fashion")))))
                .andExpect(status().isCreated()).andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        String devToken = login(DEV);
        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());

        // Upload an image deliverable.
        MockMultipartFile file = new MockMultipartFile(
                "file", "design-v1.png", "image/png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        MvcResult uploaded = mockMvc.perform(multipart("/api/jobs/" + jobId + "/assets").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.fileName").value("design-v1.png"))
                .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.sizeBytes").value(8))
                .andExpect(jsonPath("$.data.url").exists())
                .andReturn();
        String assetId = objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // It shows in the job's assets list AND on the full detail.
        mockMvc.perform(get("/api/jobs/" + jobId + "/assets").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(assetId));
        mockMvc.perform(get("/api/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assets[0].id").value(assetId));

        // Bad type → 400 (BAD_REQUEST via IllegalArgumentException).
        MockMultipartFile bad = new MockMultipartFile(
                "file", "app.exe", "application/octet-stream", new byte[]{9, 9});
        mockMvc.perform(multipart("/api/jobs/" + jobId + "/assets").file(bad)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isBadRequest());

        // Delete removes it.
        mockMvc.perform(delete("/api/jobs/" + jobId + "/assets/" + assetId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/jobs/" + jobId + "/assets").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void adminCanCrudJobTypes() throws Exception {
        String adminToken = login(ADMIN);

        // List the seeded catalogue.
        mockMvc.perform(get("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));

        // Create a new type.
        mockMvc.perform(post("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "SOCIAL_MEDIA",
                                "displayName", "Social Media",
                                "colour", "#22C55E",
                                "assignedToRoles", java.util.List.of("DESIGNER", "WRITER"),
                                "slaHours", 12,
                                "qaRequired", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("SOCIAL_MEDIA"))
                .andExpect(jsonPath("$.data.slaHours").value(12));

        // Duplicate ID → 409.
        mockMvc.perform(post("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "SOCIAL_MEDIA", "displayName", "Dup", "slaHours", 12))))
                .andExpect(status().isConflict());

        // Patch — tune SLA hours.
        mockMvc.perform(patch("/api/jobs/admin/job-types/SOCIAL_MEDIA").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slaHours", 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slaHours").value(8));

        // Soft-delete.
        mockMvc.perform(delete("/api/jobs/admin/job-types/SOCIAL_MEDIA")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        // After disable, list still includes it but active=false.
        MvcResult listed = mockMvc.perform(get("/api/jobs/admin/job-types")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode types = objectMapper.readTree(listed.getResponse().getContentAsString()).path("data");
        JsonNode social = null;
        for (JsonNode entry : types) {
            if ("SOCIAL_MEDIA".equals(entry.path("id").asText())) {
                social = entry;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(social, "SOCIAL_MEDIA should still appear after soft-delete");
        org.junit.jupiter.api.Assertions.assertFalse(social.path("active").asBoolean(),
                "soft-deleted type should be flagged active=false");

        // Developer cannot reach the admin catalogue endpoints (403).
        String devToken = login(DEV);
        mockMvc.perform(post("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "BLOCKED", "displayName", "x", "slaHours", 8))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminReadsTenantsAndUsersAcrossThePlatform() throws Exception {
        // Seed two platform tenants + their admins directly via SQL (we're testing
        // Studio's read of the platform side; Studio doesn't create tenants).
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();
        UUID userAId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            seedPlatformTenant(c, tenantAId, "Amaka Styles", "amaka-styles", "fashion", "starter");
            seedPlatformTenant(c, tenantBId, "Wellspring Pharmacy", "wellspring", "pharmacy", "pro");
            seedPlatformUser(c, userAId, tenantAId, "owner@amaka-styles.test", "Amaka", "TENANT_ADMIN");
            seedPlatformUser(c, userBId, tenantBId, "owner@wellspring.test", "Wellspring", "TENANT_ADMIN");
        }

        String adminToken = login(ADMIN);
        String devToken = login(DEV);

        // GET /platform/tenants — both seeded tenants visible to ADMIN.
        MvcResult tenants = mockMvc.perform(get("/api/jobs/admin/platform/tenants")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andReturn();
        String tenantsBody = tenants.getResponse().getContentAsString();
        assertTrue(tenantsBody.contains("Amaka Styles"));
        assertTrue(tenantsBody.contains("Wellspring Pharmacy"));

        // Search narrows by name/slug.
        mockMvc.perform(get("/api/jobs/admin/platform/tenants")
                        .param("q", "amaka")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("amaka-styles"));

        // Detail returns counts.
        mockMvc.perform(get("/api/jobs/admin/platform/tenants/" + tenantAId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Amaka Styles"))
                .andExpect(jsonPath("$.data.counts.users").value(1))
                .andExpect(jsonPath("$.data.counts.activeUsers").value(1));

        // Users-for-tenant nested list.
        mockMvc.perform(get("/api/jobs/admin/platform/tenants/" + tenantAId + "/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].email").value("owner@amaka-styles.test"));

        // Global user search.
        mockMvc.perform(get("/api/jobs/admin/platform/users")
                        .param("q", "wellspring")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantId").value(tenantBId.toString()));

        // User detail includes the tenant breadcrumb.
        mockMvc.perform(get("/api/jobs/admin/platform/users/" + userBId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.slug").value("wellspring"));

        // Unknown ids → 404.
        mockMvc.perform(get("/api/jobs/admin/platform/tenants/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/admin/platform/users/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNotFound());

        // Developer (not ADMIN) cannot reach any platform-admin endpoint.
        mockMvc.perform(get("/api/jobs/admin/platform/tenants")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jobs/admin/platform/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden());
    }

    private static void seedPlatformTenant(Connection c, UUID id, String name, String slug,
                                           String verticalId, String planId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.tenants (id, name, slug, vertical_id, plan_id) "
                        + "VALUES (?::uuid, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.setString(3, slug);
            ps.setString(4, verticalId);
            ps.setString(5, planId);
            ps.executeUpdate();
        }
    }

    private static void seedPlatformUser(Connection c, UUID id, UUID tenantId, String email,
                                         String fullName, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.users (id, tenant_id, email, password_hash, full_name, role) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
            ps.setString(1, id.toString());
            ps.setString(2, tenantId.toString());
            ps.setString(3, email);
            ps.setString(4, "hash");
            ps.setString(5, fullName);
            ps.setString(6, role);
            ps.executeUpdate();
        }
    }

    private static void seedRefreshToken(Connection c, UUID userId, UUID tenantId, String selector) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO public.refresh_tokens (user_id, tenant_id, family_id, selector, token_hash, expires_at) "
                        + "VALUES (?::uuid, ?::uuid, gen_random_uuid(), ?, 'hash', now() + interval '30 days')")) {
            ps.setString(1, userId.toString());
            ps.setString(2, tenantId.toString());
            ps.setString(3, selector);
            ps.executeUpdate();
        }
    }

    private static String tokenRevokedReason(Connection c, String selector) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revoked_reason FROM public.refresh_tokens WHERE selector = ?")) {
            ps.setString(1, selector);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void platformAdminPhase13bMutationsAndIntegrityGuards() throws Exception {
        // Seed a tenant with two TENANT_ADMINs (one we'll mutate, one to satisfy
        // last-admin protection) and a STAFF user. Plus refresh tokens for each.
        UUID tenantId = UUID.randomUUID();
        UUID adminAId = UUID.randomUUID();
        UUID adminBId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        String adminASelector = "sel-admin-a-" + UUID.randomUUID();
        String adminBSelector = "sel-admin-b-" + UUID.randomUUID();
        String staffSelector = "sel-staff-" + UUID.randomUUID();

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            seedPlatformTenant(c, tenantId, "Mutate Co", "mutate-co", "fashion", "starter");
            seedPlatformUser(c, adminAId, tenantId, "admin-a@mutate.test", "Admin A", "TENANT_ADMIN");
            seedPlatformUser(c, adminBId, tenantId, "admin-b@mutate.test", "Admin B", "TENANT_ADMIN");
            seedPlatformUser(c, staffId, tenantId, "staff@mutate.test", "Staff", "STAFF");
            seedRefreshToken(c, adminAId, tenantId, adminASelector);
            seedRefreshToken(c, adminBId, tenantId, adminBSelector);
            seedRefreshToken(c, staffId, tenantId, staffSelector);
        }

        String adminToken = login(ADMIN);

        // 1. PATCH name + planId — no side effects.
        mockMvc.perform(patch("/api/jobs/admin/platform/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Mutate Co (renamed)", "planId", "business"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Mutate Co (renamed)"))
                .andExpect(jsonPath("$.data.planId").value("business"));

        // 2. PATCH a User — promote STAFF to TENANT_ADMIN (no last-admin risk, no token revoke).
        mockMvc.perform(patch("/api/jobs/admin/platform/users/" + staffId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "tenant_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("TENANT_ADMIN"));

        // 3. Last-admin protection: try to deactivate every TENANT_ADMIN in turn.
        //    After step 2 the tenant has THREE TENANT_ADMINs (A, B, ex-staff).
        //    Deactivate A → OK (B + ex-staff still active).
        mockMvc.perform(patch("/api/jobs/admin/platform/users/" + adminAId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        // A's refresh-token family was revoked.
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    "PLATFORM_ADMIN_USER_PATCH", tokenRevokedReason(c, adminASelector));
        }

        // Now deactivate the ex-staff (TENANT_ADMIN) — B is still active.
        mockMvc.perform(patch("/api/jobs/admin/platform/users/" + staffId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk());

        // Deactivate B → only TENANT_ADMIN left → 422 LAST_ADMIN_PROTECTED.
        mockMvc.perform(patch("/api/jobs/admin/platform/users/" + adminBId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LAST_ADMIN_PROTECTED"));

        // Demoting B to STAFF is also blocked by the same guard.
        mockMvc.perform(patch("/api/jobs/admin/platform/users/" + adminBId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "STAFF"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LAST_ADMIN_PROTECTED"));

        // 4. Suspend the tenant — every active refresh token on it should be revoked.
        mockMvc.perform(patch("/api/jobs/admin/platform/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // B's token: created later but never revoked before — now should be PLATFORM_ADMIN_TENANT_SUSPENDED.
            org.junit.jupiter.api.Assertions.assertEquals(
                    "PLATFORM_ADMIN_TENANT_SUSPENDED", tokenRevokedReason(c, adminBSelector));
        }

        // 5. Soft-delete tenant — status → DELETED.
        mockMvc.perform(delete("/api/jobs/admin/platform/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/jobs/admin/platform/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));

        // 6. Password reset — platform URL unset in tests → 502 PLATFORM_API_UNAVAILABLE.
        mockMvc.perform(post("/api/jobs/admin/platform/users/" + adminBId + "/reset-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PLATFORM_API_UNAVAILABLE"));

        // 7. Audit rows landed — at least one per mutation we drove.
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM studio.platform_admin_audit_log WHERE target_id = ?::uuid")) {
            ps.setString(1, tenantId.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertTrue(rs.getLong(1) >= 3, "tenant got patched twice + deleted = 3+ audit rows");
            }
        }

        // 8. Developer cannot reach any mutator.
        String devToken = login(DEV);
        mockMvc.perform(patch("/api/jobs/admin/platform/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jobs/admin/platform/users/" + adminBId + "/reset-password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isForbidden());
    }

    /**
     * §21 Website Builder: lazy-create on PUT, optimistic locking via If-Match,
     * section catalogue validation, home-page guard, auto-publish on submit,
     * cross-staff write isolation.
     */
    @Test
    void websiteBuilderLifecycleAndGuards() throws Exception {
        String adminToken = login(ADMIN);
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Builder Test",
                                "brief", Map.of("businessName", "Builder Co", "vertical", "fashion")))))
                .andExpect(status().isCreated()).andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        String devToken = login(DEV);
        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/jobs/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());

        // 1. GET returns 404 until the first PUT.
        mockMvc.perform(get("/api/jobs/" + jobId + "/site").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isNotFound());

        // 2. PUT creates the site lazily with a home + about page, each with sections.
        Map<String, Object> putBody = Map.of(
                "theme", Map.of("primary", "#7C5CBF"),
                "meta", Map.of("title", "Builder Co"),
                "pages", java.util.List.of(
                        Map.of("slug", "home", "title", "Home", "home", true, "sections",
                                java.util.List.of(Map.of("type", "HERO",
                                        "content", Map.of("headline", "Welcome",
                                                "subheadline", "Sub", "ctaText", "Shop")))),
                        Map.of("slug", "about", "title", "About", "home", false, "sections",
                                java.util.List.of(Map.of("type", "ABOUT",
                                        "content", Map.of("heading", "About us",
                                                "body", "We make things"))))));
        MvcResult putResult = mockMvc.perform(put("/api/jobs/" + jobId + "/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(putBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.pages.length()").value(2))
                .andExpect(jsonPath("$.data.pages[0].slug").value("home"))
                .andExpect(jsonPath("$.data.pages[0].sections[0].type").value("HERO"))
                .andReturn();
        int versionAfterPut = objectMapper.readTree(putResult.getResponse().getContentAsString())
                .path("data").path("version").asInt();
        String homePageId = objectMapper.readTree(putResult.getResponse().getContentAsString())
                .path("data").path("pages").get(0).path("id").asText();

        // 3. Stale If-Match → 409 VERSION_MISMATCH.
        mockMvc.perform(patch("/api/jobs/" + jobId + "/site/theme")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .header(HttpHeaders.IF_MATCH, "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "theme", Map.of("primary", "#FF0000")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_MISMATCH"));

        // 4. PATCH theme with current version succeeds and bumps version.
        MvcResult themePatched = mockMvc.perform(patch("/api/jobs/" + jobId + "/site/theme")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .header(HttpHeaders.IF_MATCH, String.valueOf(versionAfterPut))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "theme", Map.of("primary", "#FF0000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme.primary").value("#FF0000"))
                .andReturn();
        int versionAfterTheme = objectMapper.readTree(themePatched.getResponse().getContentAsString())
                .path("data").path("version").asInt();
        assertTrue(versionAfterTheme > versionAfterPut, "theme patch must bump version");

        // 5. Section catalogue: unknown type → 400.
        mockMvc.perform(post("/api/jobs/" + jobId + "/site/pages/" + homePageId + "/sections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .header(HttpHeaders.IF_MATCH, String.valueOf(versionAfterTheme))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "BOGUS", "content", Map.of("foo", "bar")))))
                .andExpect(status().isBadRequest());

        // 6. Missing required key on a valid type → 400.
        mockMvc.perform(post("/api/jobs/" + jobId + "/site/pages/" + homePageId + "/sections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .header(HttpHeaders.IF_MATCH, String.valueOf(versionAfterTheme))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "HERO", "content", Map.of()))))
                .andExpect(status().isBadRequest());

        // 7. Home-page deletion is refused → 422 HOME_PAGE_REQUIRED.
        mockMvc.perform(delete("/api/jobs/" + jobId + "/site/pages/" + homePageId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken))
                        .header(HttpHeaders.IF_MATCH, String.valueOf(versionAfterTheme)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("HOME_PAGE_REQUIRED"));

        // 8. Cross-developer write isolation — Dev B cannot mutate Dev A's site.
        String secEmail = "site-dev-b-" + UUID.randomUUID() + "@studio.test";
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            insertStaff(c, secEmail, new BCryptPasswordEncoder(12).encode(PW),
                    "Site Dev B", "DEVELOPER", "[\"WEBSITE_BUILD\"]");
        }
        String devBToken = login(secEmail);
        mockMvc.perform(patch("/api/jobs/" + jobId + "/site/theme")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "theme", Map.of("primary", "#00FF00")))))
                .andExpect(status().isConflict());

        // 9. Auto-publish on submit: assignee submits → site.status flips to PUBLISHED.
        mockMvc.perform(post("/api/jobs/" + jobId + "/submit")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/jobs/" + jobId + "/site")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedAt").exists());
    }

    /**
     * §22 Export + Import: streams a checksummed ZIP; import verifies checksum,
     * job-id match, and applies brief + ai-suggestions back.
     */
    @Test
    void jobExportImportRoundtripAndIntegrityGuards() throws Exception {
        String adminToken = login(ADMIN);
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Export Test",
                                "brief", Map.of("businessName", "Export Co", "vertical", "fashion")))))
                .andExpect(status().isCreated()).andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        String devToken = login(DEV);
        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk());

        // 1. Export returns a ZIP with the right content-type and disposition headers.
        MvcResult exportResult = mockMvc.perform(get("/api/jobs/" + jobId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString(".conddo-studio.zip")))
                .andExpect(header().exists("X-Bundle-Checksum"))
                .andReturn();
        byte[] zipBytes = exportResult.getResponse().getContentAsByteArray();
        assertTrue(zipBytes.length > 0, "bundle should not be empty");
        Map<String, byte[]> bundleFiles = readZipBytes(zipBytes);
        byte[] manifestBytes = bundleFiles.get("manifest.json");
        org.junit.jupiter.api.Assertions.assertNotNull(manifestBytes,
                "bundle must contain manifest.json");
        com.fasterxml.jackson.databind.JsonNode manifest = objectMapper.readTree(manifestBytes);
        assertEquals(1, manifest.path("schemaVersion").asInt());
        assertEquals(jobId, manifest.path("job").path("id").asText());
        assertTrue(manifest.path("checksum").asText().startsWith("sha256:"));
        org.junit.jupiter.api.Assertions.assertNotNull(bundleFiles.get("brief.md"));
        org.junit.jupiter.api.Assertions.assertNotNull(bundleFiles.get("README.md"));

        // 2. Round-trip import succeeds.
        MockMultipartFile file = new MockMultipartFile("file", "round.zip", "application/zip", zipBytes);
        mockMvc.perform(multipart("/api/jobs/" + jobId + "/import").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId));

        // 3. Wrong job id → 422 JOB_MISMATCH.
        MvcResult otherCreated = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Other Job",
                                "brief", Map.of("businessName", "Other Co")))))
                .andExpect(status().isCreated()).andReturn();
        String otherJobId = objectMapper.readTree(otherCreated.getResponse().getContentAsString())
                .path("data").path("id").asText();
        MockMultipartFile mismatched = new MockMultipartFile("file", "mismatch.zip", "application/zip", zipBytes);
        mockMvc.perform(multipart("/api/jobs/" + otherJobId + "/import").file(mismatched)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("JOB_MISMATCH"));

        // 4. Tampered bundle → 422 BUNDLE_TAMPERED.
        byte[] tampered = corruptBundle(zipBytes);
        MockMultipartFile bad = new MockMultipartFile("file", "tampered.zip", "application/zip", tampered);
        mockMvc.perform(multipart("/api/jobs/" + jobId + "/import").file(bad)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUNDLE_TAMPERED"));

        // 5. Missing manifest → 422 BUNDLE_TAMPERED.
        byte[] noManifest = buildBundleWithoutManifest();
        MockMultipartFile missing = new MockMultipartFile("file", "no-manifest.zip", "application/zip", noManifest);
        mockMvc.perform(multipart("/api/jobs/" + jobId + "/import").file(missing)
                        .header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUNDLE_TAMPERED"));
    }

    /** Read every entry of a ZIP into a name→bytes map for assertions. */
    private static Map<String, byte[]> readZipBytes(byte[] zipBytes) throws java.io.IOException {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                zis.transferTo(bo);
                out.put(entry.getName(), bo.toByteArray());
            }
        }
        return out;
    }

    /** Flip the brief.md bytes so the checksum no longer matches. */
    private static byte[] corruptBundle(byte[] zipBytes) throws java.io.IOException {
        Map<String, byte[]> files = readZipBytes(zipBytes);
        byte[] brief = files.get("brief.md");
        if (brief != null && brief.length > 0) {
            brief[0] ^= (byte) 0xFF;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Build a zip with just brief.md — no manifest. */
    private static byte[] buildBundleWithoutManifest() throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("brief.md"));
            zos.write("# fake".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void unauthenticatedIsRejectedAndBadLoginFails() throws Exception {
        mockMvc.perform(get("/api/jobs/my-jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/jobs/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", DEV, "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    /**
     * Cross-staff security audit (Slice G / Phase 10 §7). Pins the membership
     * rules so a future refactor of {@code @PreAuthorize}/{@code requireAssigned}
     * doesn't silently widen the blast radius. A developer must not be able to
     * mutate another developer's job, the QA queue is QA-only, and the admin
     * surface is admin/lead-only.
     *
     * <p>Uses only fresh staff members (seeded inside this test) so it never
     * pollutes state visible to other tests (notifications, claims, etc).
     */
    @Test
    void crossStaffSecurityAuditAllRoleBoundaries() throws Exception {
        // Isolated staff so no other test sees notifications/claims from this run.
        String secEmailA = "sec-dev-a@studio.test";
        String secEmailB = "sec-dev-b@studio.test";
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            String hash = new BCryptPasswordEncoder(12).encode(PW);
            insertStaff(c, secEmailA, hash, "Sec Dev A", "DEVELOPER", "[\"WEBSITE_BUILD\"]");
            insertStaff(c, secEmailB, hash, "Sec Dev B", "DEVELOPER", "[\"WEBSITE_BUILD\"]");
        }

        String adminToken = login(ADMIN);
        String devAToken = login(secEmailA);
        String devBToken = login(secEmailB);
        String qaToken = login(QA);

        // Admin creates a job; Sec Dev A claims it.
        MvcResult created = mockMvc.perform(post("/api/jobs/admin/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "jobTypeId", "WEBSITE_BUILD", "title", "Security Audit Job",
                                "brief", Map.of("businessName", "AuditCo", "vertical", "fashion")))))
                .andExpect(status().isCreated()).andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(post("/api/jobs/" + jobId + "/claim").header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(status().isOk());

        // 1. Dev B cannot start Dev A's job (assigned-only enforcement).
        mockMvc.perform(patch("/api/jobs/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(devBToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // 2. Dev B cannot submit Dev A's job either.
        mockMvc.perform(post("/api/jobs/" + jobId + "/submit").header(HttpHeaders.AUTHORIZATION, bearer(devBToken)))
                .andExpect(status().isConflict());

        // 3. Dev B cannot reassign someone else's job (admin-only).
        mockMvc.perform(patch("/api/jobs/admin/" + jobId + "/reassign")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("staffId", java.util.UUID.randomUUID()))))
                .andExpect(status().isForbidden());

        // 4. Dev B cannot escalate / extend SLA / mutate the admin catalogue.
        mockMvc.perform(patch("/api/jobs/admin/" + jobId + "/escalate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devBToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(devBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "BLOCKED_TYPE", "displayName", "x", "slaHours", 8))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jobs/admin/design-standards").header(HttpHeaders.AUTHORIZATION, bearer(devBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "kind", "PALETTE", "name", "Blocked palette"))))
                .andExpect(status().isForbidden());

        // 5. QA also cannot mutate the ADMIN-only writes (job-types).
        mockMvc.perform(post("/api/jobs/admin/job-types").header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", "QA_BLOCKED", "displayName", "x", "slaHours", 8))))
                .andExpect(status().isForbidden());

        // 6. Developers cannot reach the QA queue / QA scan — QA-only endpoints.
        mockMvc.perform(get("/api/jobs/qa/queue").header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jobs/qa/" + jobId + "/scan").header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(status().isForbidden());

        // 7. Unauthenticated access to the SSE stream fails.
        mockMvc.perform(get("/api/jobs/events"))
                .andExpect(status().isUnauthorized());

        // 8. Mark-all-read is scoped to the caller — Dev B's read-all doesn't drop Dev A's count.
        //    Drive a QA revision against Dev A so they have an unread notification.
        mockMvc.perform(patch("/api/jobs/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/" + jobId + "/submit").header(HttpHeaders.AUTHORIZATION, bearer(devAToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("studioUrl", "https://x"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/start").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/jobs/qa/" + jobId + "/return").header(HttpHeaders.AUTHORIZATION, bearer(qaToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("feedback", "fix it"))))
                .andExpect(status().isOk());
        // Dev A has at least one unread; Dev B has zero (no notifications were ever sent to them).
        mockMvc.perform(get("/api/jobs/notifications").param("unread", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(jsonPath("$.data.unread").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        // Dev B hits read-all → only their own (zero) count gets touched.
        mockMvc.perform(patch("/api/jobs/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devBToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(0));
        // Dev A is still unread — no cross-staff leak.
        mockMvc.perform(get("/api/jobs/notifications").param("unread", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(devAToken)))
                .andExpect(jsonPath("$.data.unread").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ----- helpers ------------------------------------------------------------

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PW))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static void insertStaff(Connection c, String email, String hash, String name,
                                    String role, String skillsJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO studio.staff (email, password_hash, full_name, role, skills) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, email);
            ps.setString(2, hash);
            ps.setString(3, name);
            ps.setString(4, role);
            ps.setString(5, skillsJson);
            ps.executeUpdate();
        }
    }
}
