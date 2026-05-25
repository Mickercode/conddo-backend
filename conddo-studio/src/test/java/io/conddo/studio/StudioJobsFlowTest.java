package io.conddo.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.auth.StudioServiceTokenFilter;
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
import java.sql.SQLException;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
            .withDatabaseName("conddo").withUsername("conddo_owner").withPassword("owner_password");

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
        mockMvc.perform(get("/api/jobs/available").header(HttpHeaders.AUTHORIZATION, bearer(devToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
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
        mockMvc.perform(get("/api/jobs/qa/queue").header(HttpHeaders.AUTHORIZATION, bearer(qaToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
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
    void unauthenticatedIsRejectedAndBadLoginFails() throws Exception {
        mockMvc.perform(get("/api/jobs/my-jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/jobs/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", DEV, "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
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
