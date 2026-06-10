package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.ai.AnthropicGateway;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Module Spec v2 §12C — AI Product Assistant.
 *
 * <p>The {@link AnthropicGateway} is mocked so the BE flow tests don't
 * touch a real Anthropic API (and so we can pin the prompt → JSON
 * contract). Coverage:
 * <ul>
 *   <li>product-from-image: clean JSON path, markdown-fence-wrapped
 *       path (model often returns ```json blocks), non-JSON garbage
 *       path → 502.</li>
 *   <li>description: nameGeneric + indications → suggestion shape.</li>
 *   <li>503 when the gateway is the dormant impl (no API key) — the
 *       handler turns it into AI_NOT_CONFIGURED rather than 500.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyAiAssistantFlowTest {

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
    @MockBean
    private AnthropicGateway anthropicGateway;

    @Test
    void productFromImageReturnsStructuredSuggestionAndDisclaimer() throws Exception {
        when(anthropicGateway.chatWithImage(eq("https://cdn.test/img.jpg"), anyString())).thenReturn("""
                {
                  "nameGeneric": "Amoxicillin",
                  "nameBrand": "Amoxil",
                  "description": "Broad-spectrum penicillin antibiotic.",
                  "indications": "Bacterial infections of the respiratory and urinary tract.",
                  "dosageGuidance": "Adults: 500mg three times daily.",
                  "warnings": "Do not use if allergic to penicillin.",
                  "storage": "Store below 25°C.",
                  "nafdacNumber": "A4-1234",
                  "brand": "GlaxoSmithKline",
                  "requiresPrescription": true,
                  "suggestedCategory": "prescription",
                  "confidence": "high"
                }""");

        String token = signupAndLogin("ai-img", "owner@ai-img.test");
        mockMvc.perform(post("/api/v1/pharmacy/ai/product-from-image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageUrl", "https://cdn.test/img.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestion.nameGeneric").value("Amoxicillin"))
                .andExpect(jsonPath("$.data.suggestion.requiresPrescription").value(true))
                .andExpect(jsonPath("$.data.suggestion.suggestedCategory").value("prescription"))
                .andExpect(jsonPath("$.data.confidence").value("high"))
                .andExpect(jsonPath("$.data.note").value(
                        org.hamcrest.Matchers.containsString("not a substitute")));
    }

    @Test
    void markdownFencedJsonIsStrippedAndParsed() throws Exception {
        when(anthropicGateway.chatWithImage(anyString(), anyString())).thenReturn("""
                ```json
                { "nameGeneric": "Paracetamol", "requiresPrescription": false, "confidence": "medium" }
                ```""");

        String token = signupAndLogin("ai-fence", "owner@ai-fence.test");
        mockMvc.perform(post("/api/v1/pharmacy/ai/product-from-image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageUrl", "https://cdn.test/p.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestion.nameGeneric").value("Paracetamol"))
                .andExpect(jsonPath("$.data.suggestion.requiresPrescription").value(false))
                .andExpect(jsonPath("$.data.confidence").value("medium"));
    }

    @Test
    void nonJsonResponseSurfacesAs502AiUnavailable() throws Exception {
        when(anthropicGateway.chatWithImage(anyString(), anyString())).thenReturn(
                "Sorry, I can't read this image.");

        String token = signupAndLogin("ai-junk", "owner@ai-junk.test");
        mockMvc.perform(post("/api/v1/pharmacy/ai/product-from-image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageUrl", "https://cdn.test/x.jpg"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void descriptionReturnsTwoFieldShape() throws Exception {
        when(anthropicGateway.chatText(anyString())).thenReturn("""
                {
                  "description": "Metformin is a first-line oral antidiabetic medication.",
                  "warnings": "Do not use in patients with renal impairment (eGFR < 30)."
                }""");

        String token = signupAndLogin("ai-desc", "owner@ai-desc.test");
        mockMvc.perform(post("/api/v1/pharmacy/ai/description")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nameGeneric", "Metformin",
                                "nameBrand", "Glucophage",
                                "indications", "Type 2 diabetes"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value(
                        org.hamcrest.Matchers.containsString("Metformin")))
                .andExpect(jsonPath("$.data.warnings").value(
                        org.hamcrest.Matchers.containsString("renal impairment")));
    }

    @Test
    void gatewayNotConfiguredSurfacesAs503AiNotConfigured() throws Exception {
        when(anthropicGateway.chatWithImage(anyString(), anyString())).thenThrow(
                new AnthropicGateway.AnthropicNotConfiguredException());

        String token = signupAndLogin("ai-off", "owner@ai-off.test");
        mockMvc.perform(post("/api/v1/pharmacy/ai/product-from-image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "imageUrl", "https://cdn.test/x.jpg"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_NOT_CONFIGURED"));
    }

    // ----- helpers ----------------------------------------------------------

    private String signupAndLogin(String slug, String adminEmail) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
        MvcResult res = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", slug, "email", adminEmail, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
