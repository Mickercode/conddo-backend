package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.domain.Tenant;
import io.conddo.core.notify.BrevoEmailSender;
import io.conddo.core.notify.NotificationProperties;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.notify.TenantEmailBranding;
import io.conddo.core.notify.TenantEmailBrandingResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant email branding (V52). Covers both the settings surface and
 * the BrevoEmailSender wiring — when a tenant is bound, the
 * outbound Brevo payload carries the tenant's display name as
 * sender + the tenant's contact email (or override) as reply-to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantEmailBrandingFlowTest {

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
    private io.conddo.core.notify.EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @Test
    void getReturnsDefaultEffectiveFromNameAndReplyTo() throws Exception {
        signup("brand-defaults", "owner@brand-defaults.test");
        String token = login("brand-defaults", "owner@brand-defaults.test");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/settings/email-branding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.fromName").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.effectiveFromName").value("brand-defaults Business"));
    }

    @Test
    void putOverrideThenClearItFallsBackToDefaults() throws Exception {
        signup("brand-pp", "owner@brand-pp.test");
        String token = login("brand-pp", "owner@brand-pp.test");

        // Set business contact email so replyTo has a default.
        mockMvc.perform(put("/api/v1/settings/business-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Brand PP", "email", "support@brand-pp.com.ng"))))
                .andExpect(status().isOk());

        // Override the fromName + replyTo.
        mockMvc.perform(put("/api/v1/settings/email-branding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fromName", "Brand PP Pharmacy",
                                "replyTo", "noreply@brand-pp.com.ng"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.fromName").value("Brand PP Pharmacy"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.effectiveReplyTo").value("noreply@brand-pp.com.ng"));

        // Clear the fromName override → falls back to business name.
        mockMvc.perform(put("/api/v1/settings/email-branding")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "replyTo", "noreply@brand-pp.com.ng"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.fromName").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.effectiveFromName").value("Brand PP"));
    }

    @Test
    void brevoEmailSenderAppliesTenantBrandingToOutboundPayload() {
        // Pure unit test — no Spring context, just verifies the
        // BrevoEmailSender → Brevo HTTP shape applies tenant branding
        // when the resolver reports one.
        TenantEmailBrandingResolver resolver = mock(TenantEmailBrandingResolver.class);
        when(resolver.currentBranding()).thenReturn(Optional.of(
                new TenantEmailBranding("Wellspring Pharmacy", "orders@wellspring.com.ng")));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificationProperties props = new NotificationProperties(
                new NotificationProperties.Sms("brevo", "https://api.brevo.com", "k", "Conddo"),
                new NotificationProperties.Email("brevo", "https://api.brevo.com", "api-k",
                        "noreply@conddo.io", "Conddo", null, null));
        BrevoEmailSender sender = new BrevoEmailSender(props, builder, resolver);

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sender.name").value("Wellspring Pharmacy"))
                .andExpect(jsonPath("$.sender.email").value("noreply@conddo.io"))
                .andExpect(jsonPath("$.replyTo.email").value("orders@wellspring.com.ng"))
                .andExpect(jsonPath("$.to[0].email").value("customer@x.test"))
                .andExpect(jsonPath("$.subject").value("Your order is confirmed"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.send("customer@x.test", "Your order is confirmed", "Hi from Wellspring");
        server.verify();
    }

    @Test
    void brevoEmailSenderUsesGlobalDefaultWhenNoTenantBound() {
        TenantEmailBrandingResolver resolver = mock(TenantEmailBrandingResolver.class);
        when(resolver.currentBranding()).thenReturn(Optional.empty());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificationProperties props = new NotificationProperties(
                new NotificationProperties.Sms("brevo", "https://api.brevo.com", "k", "Conddo"),
                new NotificationProperties.Email("brevo", "https://api.brevo.com", "api-k",
                        "noreply@conddo.io", "Conddo", null, null));
        BrevoEmailSender sender = new BrevoEmailSender(props, builder, resolver);

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sender.name").value("Conddo"))
                .andExpect(jsonPath("$.replyTo").doesNotExist())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.send("invitee@x.test", "Welcome", "You have been invited");
        server.verify();
    }

    // ----- helpers ----------------------------------------------------------

    private void signup(String slug, String adminEmail) throws Exception {
        mockMvc.perform(post("/api/v1/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", slug + " Business", "slug", slug,
                                "adminEmail", adminEmail, "adminPassword", PASSWORD))))
                .andExpect(status().isCreated());
    }

    private String login(String slug, String email) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", slug, "email", email, "password", PASSWORD))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
