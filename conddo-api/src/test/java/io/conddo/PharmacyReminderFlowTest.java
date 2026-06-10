package io.conddo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.api.pharmacy.PharmacyDiscountExpiryScheduler;
import io.conddo.api.pharmacy.PharmacyReminderScheduler;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pharmacy Module Spec v2 §12D — Reminders, plus the deferred cron
 * jobs from §12B (discount expiry) and §12E (refill-offer SMS).
 *
 * <ul>
 *   <li>Create → list → cancel.</li>
 *   <li>Scheduler dispatches a due SMS with template interpolation
 *       (firstName / productName / storeName).</li>
 *   <li>Discount expiry sweeper flips an APPROVED row past ends_at
 *       to EXPIRED.</li>
 *   <li>Refill offer issue with {@code sendSms=true} queues a
 *       SCHEDULED reminder with the offer code pre-interpolated.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PharmacyReminderFlowTest {

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
        // Pin every cron we don't drive directly to far-future so they
        // never fire mid-test. We call runOnce() explicitly where we need
        // them.
        registry.add("conddo.billing.expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.reminder-cron", () -> "0 0 0 1 1 ?");
        registry.add("conddo.pharmacy.discount-expiry-cron", () -> "0 0 0 1 1 ?");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PharmacyReminderScheduler reminderScheduler;
    @Autowired
    private PharmacyDiscountExpiryScheduler discountExpiryScheduler;
    @MockBean
    private EmailSender emailSender;
    @MockBean
    private SmsSender smsSender;

    @Test
    void createCancelAndSchedulerDispatchesWithInterpolation() throws Exception {
        String tenantId = signup("rem-flow", "owner@rem-flow.test");
        String token = login("rem-flow", "owner@rem-flow.test");
        String customerId = seedCustomerWithPhone(tenantId, "Sarah Okafor", "+2348091234567");
        String pid = seedProduct(tenantId, "Amlodipine");

        // Create a reminder scheduled in the past so the scheduler picks
        // it up immediately when we call runOnce().
        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/reminders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "productId", pid,
                                "reminderType", "REFILL_DUE",
                                "message", "Hi {firstName}, your {productName} refill is due. — {storeName}",
                                "scheduledAt", OffsetDateTime.now().minusMinutes(5).toString(),
                                "recurrence", "ONCE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reminder.status").value("SCHEDULED"))
                .andReturn();
        String reminderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("reminder").path("id").asText();

        // Cron tick — SMS goes out with the interpolated message.
        reminderScheduler.runOnce();

        ArgumentCaptor<String> messageCap = ArgumentCaptor.forClass(String.class);
        verify(smsSender, timeout(2_000).atLeastOnce())
                .send(eq("+2348091234567"), messageCap.capture());
        String message = messageCap.getValue();
        assertTrue(message.contains("Sarah"), "firstName interpolated: " + message);
        assertTrue(message.contains("Amlodipine"), "productName interpolated: " + message);
        assertTrue(message.contains("rem-flow Business"), "storeName interpolated: " + message);

        assertEquals("SENT", readReminderStatus(reminderId), "reminder should be marked SENT");
    }

    @Test
    void scheduledReminderCanBeCancelled() throws Exception {
        String tenantId = signup("rem-cancel", "owner@rem-cancel.test");
        String token = login("rem-cancel", "owner@rem-cancel.test");
        String customerId = seedCustomerWithPhone(tenantId, "Bob Buyer", "+2348091230000");

        MvcResult created = mockMvc.perform(post("/api/v1/pharmacy/reminders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "reminderType", "CUSTOM",
                                "message", "Hi {firstName} — friendly check-in.",
                                "scheduledAt", OffsetDateTime.now().plusDays(7).toString(),
                                "recurrence", "ONCE"))))
                .andExpect(status().isCreated())
                .andReturn();
        String reminderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("reminder").path("id").asText();

        mockMvc.perform(patch("/api/v1/pharmacy/reminders/" + reminderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminder.status").value("CANCELLED"));

        // Cron tick — cancelled row is NOT touched.
        reminderScheduler.runOnce();
        assertEquals("CANCELLED", readReminderStatus(reminderId));
    }

    @Test
    void discountExpirySchedulerFlipsApprovedRowsPastEndsAt() throws Exception {
        String tenantId = signup("disc-exp", "owner@disc-exp.test");
        String token = login("disc-exp", "owner@disc-exp.test");
        String pid = seedProduct(tenantId, "Vitamin C");

        // Create + approve a discount whose ends_at is yesterday.
        MvcResult res = mockMvc.perform(post("/api/v1/pharmacy/discounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "discountType", "PERCENTAGE",
                                "discountValue", 10,
                                "label", "Should expire",
                                "startsAt", OffsetDateTime.now().minusDays(7).toString(),
                                "endsAt", OffsetDateTime.now().plusHours(1).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        String discountId = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("discount").path("id").asText();
        mockMvc.perform(patch("/api/v1/pharmacy/discounts/" + discountId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("action", "APPROVE"))))
                .andExpect(status().isOk());

        // Backdate ends_at to the past so the sweeper picks it up.
        backdateDiscountEndsAt(discountId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        discountExpiryScheduler.runOnce();

        assertEquals("EXPIRED", readDiscountStatus(discountId));
    }

    @Test
    void refillOfferIssueWithSendSmsQueuesReminder() throws Exception {
        String tenantId = signup("ref-sms", "owner@ref-sms.test");
        String token = login("ref-sms", "owner@ref-sms.test");
        String customerId = seedCustomerWithPhone(tenantId, "Carol Patient", "+2348099998877");
        String pid = seedProduct(tenantId, "Metformin");

        // Create offer with message template that references {offerCode}.
        MvcResult offerRes = mockMvc.perform(post("/api/v1/pharmacy/refill-offers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productId", pid,
                                "discountType", "PERCENTAGE",
                                "discountValue", 10,
                                "validDays", 30,
                                "maxUses", 1,
                                "message", "Hi {firstName}, refill your {productName} within {validDays} days. Use {offerCode}."))))
                .andExpect(status().isCreated())
                .andReturn();
        String offerId = objectMapper.readTree(offerRes.getResponse().getContentAsString())
                .path("data").path("offer").path("id").asText();

        // Issue with sendSms=true — a SCHEDULED reminder should land.
        MvcResult issueRes = mockMvc.perform(post("/api/v1/pharmacy/refill-offers/" + offerId + "/issue")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "sendSms", true))))
                .andExpect(status().isCreated())
                .andReturn();
        String offerCode = objectMapper.readTree(issueRes.getResponse().getContentAsString())
                .path("data").path("claim").path("offerCode").asText();

        // The reminder row should contain the OFFER message with
        // {offerCode}/{validDays} pre-interpolated.
        String reminderMessage = readQueuedReminderMessage(tenantId, customerId);
        assertTrue(reminderMessage.contains(offerCode),
                "reminder should carry the offer code: " + reminderMessage);
        assertTrue(reminderMessage.contains("30 days"),
                "reminder should carry validDays: " + reminderMessage);
        assertTrue(reminderMessage.contains("{firstName}"),
                "firstName stays as a token until scheduler send: " + reminderMessage);

        // Cron tick — SMS goes out with all four tokens interpolated.
        reminderScheduler.runOnce();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(smsSender, timeout(2_000).atLeastOnce())
                .send(eq("+2348099998877"), captor.capture());
        String dispatched = captor.getValue();
        assertTrue(dispatched.contains("Carol"), "firstName: " + dispatched);
        assertTrue(dispatched.contains("Metformin"), "productName: " + dispatched);
        assertTrue(dispatched.contains(offerCode), "offerCode: " + dispatched);
        assertTrue(dispatched.contains("30 days"), "validDays: " + dispatched);
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

    private String seedCustomerWithPhone(String tenantId, String name, String phone) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "INSERT INTO customers (tenant_id, full_name, email, phone) "
                             + "VALUES (?::uuid, ?, ?, ?) RETURNING id")) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase().replace(' ', '.') + "@buyer.test");
            ps.setString(4, phone);
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

    private String readReminderStatus(String reminderId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status FROM pharmacy_reminders WHERE id = ?::uuid")) {
            ps.setString(1, reminderId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String readDiscountStatus(String discountId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT status FROM pharmacy_discounts WHERE id = ?::uuid")) {
            ps.setString(1, discountId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private void backdateDiscountEndsAt(String discountId, OffsetDateTime ends) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "UPDATE pharmacy_discounts SET ends_at = ? WHERE id = ?::uuid")) {
            ps.setObject(1, ends);
            ps.setString(2, discountId);
            ps.executeUpdate();
        }
    }

    private String readQueuedReminderMessage(String tenantId, String customerId) throws SQLException {
        try (Connection owner = ownerConn();
             PreparedStatement ps = owner.prepareStatement(
                     "SELECT message FROM pharmacy_reminders "
                             + "WHERE tenant_id = ?::uuid AND customer_id = ?::uuid "
                             + "AND status = 'SCHEDULED' ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, tenantId);
            ps.setString(2, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "queued reminder row must exist");
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
