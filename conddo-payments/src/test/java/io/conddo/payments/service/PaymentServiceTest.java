package io.conddo.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.payments.common.ConflictException;
import io.conddo.payments.common.NotFoundException;
import io.conddo.payments.common.RoutePayUnavailableException;
import io.conddo.payments.domain.Payment;
import io.conddo.payments.domain.PaymentStatus;
import io.conddo.payments.domain.TenantAccount;
import io.conddo.payments.domain.TenantAccountStatus;
import io.conddo.payments.repository.PaymentRepository;
import io.conddo.payments.repository.TenantAccountRepository;
import io.conddo.payments.repository.WebhookEventRepository;
import io.conddo.payments.routepay.RoutePayClient;
import io.conddo.payments.routepay.RoutePayWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the orchestration contract: tenant provisioning is idempotent + falls
 * back to PROVISIONING_FAILED on RoutePay outage; initPayment refuses without
 * a tenant account and without amount/either-or invariants; webhook dedupes
 * by event id AND payload hash, terminalises only once, and notifies
 * conddo-api after a state change.
 */
class PaymentServiceTest {

    private final TenantAccountRepository tenantAccounts = mock(TenantAccountRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final WebhookEventRepository webhookEvents = mock(WebhookEventRepository.class);
    private final RoutePayClient routePay = mock(RoutePayClient.class);
    private final RoutePayWebhookVerifier verifier = mock(RoutePayWebhookVerifier.class);
    private final ConddoApiNotifyClient notifyClient = mock(ConddoApiNotifyClient.class);

    private final PaymentService service = new PaymentService(
            tenantAccounts, payments, webhookEvents, routePay, verifier, notifyClient,
            new ObjectMapper(), 250);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    // ----- tenant provisioning ------------------------------------------------

    @Test
    void provisionTenantAccountCreatesAndActivatesOnRoutePaySuccess() {
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.empty());
        when(routePay.createSubAccount(eq(tenantId), eq("Amaka Styles"), eq("owner@amaka.test")))
                .thenReturn(new RoutePayClient.SubAccountResult("RP-SUB-1"));
        when(tenantAccounts.save(any(TenantAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantAccount account = service.provisionTenantAccount(tenantId, "amaka",
                "Amaka Styles", "owner@amaka.test");

        assertEquals(TenantAccountStatus.ACTIVE, account.getStatus());
        assertEquals("RP-SUB-1", account.getRoutepaySubaccountId());
    }

    @Test
    void provisionTenantAccountFallsBackToPROVISIONING_FAILEDOnRoutePayDown() {
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.empty());
        when(routePay.createSubAccount(any(), any(), any()))
                .thenThrow(new RoutePayUnavailableException("503"));
        when(tenantAccounts.save(any(TenantAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantAccount account = service.provisionTenantAccount(tenantId, "amaka",
                "Amaka Styles", "owner@amaka.test");

        assertEquals(TenantAccountStatus.PROVISIONING_FAILED, account.getStatus());
        assertNull(account.getRoutepaySubaccountId());
    }

    @Test
    void provisionTenantAccountIsIdempotent() {
        TenantAccount existing = activeAccount();
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.of(existing));

        TenantAccount account = service.provisionTenantAccount(tenantId, "amaka", "Amaka", "owner@amaka.test");

        assertEquals(existing, account);
        verify(routePay, never()).createSubAccount(any(), any(), any());
        verify(tenantAccounts, never()).save(any());
    }

    // ----- initPayment --------------------------------------------------------

    @Test
    void initPaymentRequiresExactlyOneOfOrderOrBooking() {
        // Neither.
        assertThrows(IllegalArgumentException.class, () -> service.initPayment(tenantId, "amaka",
                new PaymentService.InitPaymentInput(null, null, null,
                        "c@x.test", "Cust", "desc", "https://x", 1000L)));
        // Both.
        assertThrows(IllegalArgumentException.class, () -> service.initPayment(tenantId, "amaka",
                new PaymentService.InitPaymentInput(orderId, UUID.randomUUID(), null,
                        "c@x.test", "Cust", "desc", "https://x", 1000L)));
    }

    @Test
    void initPaymentRejectsZeroOrNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> service.initPayment(tenantId, "amaka",
                new PaymentService.InitPaymentInput(orderId, null, null,
                        "c@x.test", "Cust", "desc", "https://x", 0L)));
    }

    @Test
    void initPayment404sWhenTenantAccountMissing() {
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.initPayment(tenantId, "amaka",
                new PaymentService.InitPaymentInput(orderId, null, null,
                        "c@x.test", "Cust", "desc", "https://x", 1000L)));
    }

    @Test
    void initPaymentConflictsWhenTenantAccountSuspended() {
        TenantAccount suspended = activeAccount();
        suspended.suspend();
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.of(suspended));
        assertThrows(ConflictException.class, () -> service.initPayment(tenantId, "amaka",
                new PaymentService.InitPaymentInput(orderId, null, null,
                        "c@x.test", "Cust", "desc", "https://x", 1000L)));
    }

    @Test
    void initPaymentSucceedsAndPassesPlatformFeeBpsThrough() {
        when(tenantAccounts.findById(tenantId)).thenReturn(Optional.of(activeAccount()));
        when(payments.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routePay.initPayment(any())).thenReturn(
                new RoutePayClient.InitPaymentResult("RP-TXN-1", "https://pay/x"));

        Payment p = service.initPayment(tenantId, "amaka", new PaymentService.InitPaymentInput(
                orderId, null, null, "c@x.test", "Cust Mer", "Dress", "https://r", 25_000L));

        assertEquals(PaymentStatus.PENDING, p.getStatus());
        assertEquals(25_000L, p.getAmountKobo());
        ArgumentCaptor<RoutePayClient.InitPaymentRequest> req =
                ArgumentCaptor.forClass(RoutePayClient.InitPaymentRequest.class);
        verify(routePay).initPayment(req.capture());
        assertEquals(250, req.getValue().platformFeeBps(), "configured platform fee bps must reach RoutePay");
        assertEquals("RP-SUB-1", req.getValue().tenantSubaccountId());
    }

    // ----- webhook ------------------------------------------------------------

    @Test
    void webhookRejectsInvalidSignature() {
        when(verifier.verify(any(), any())).thenReturn(false);
        PaymentService.WebhookResult result = service.handleWebhook(new byte[]{1}, "bad-sig");
        assertTrue(!result.processed());
        assertEquals("INVALID_SIGNATURE", result.reason());
        verify(payments, never()).save(any());
    }

    @Test
    void webhookDedupesByPayloadHash() {
        byte[] body = "{\"reference\":\"RP-1\",\"status\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(verifier.sha256Hex(any())).thenReturn("hash-1");
        when(webhookEvents.findByRoutepayEventId(any())).thenReturn(Optional.empty());
        when(webhookEvents.findFirstByPayloadSha256("hash-1")).thenReturn(
                Optional.of(new io.conddo.payments.domain.WebhookEvent(null, "sig", "hash-1")));

        PaymentService.WebhookResult result = service.handleWebhook(body, "sig");

        assertEquals("DUPLICATE", result.reason());
        verify(payments, never()).save(any());
    }

    @Test
    void webhookTerminalisesPendingPaymentAndNotifiesConddoApi() {
        byte[] body = "{\"reference\":\"RP-1\",\"status\":\"PAID\",\"fee\":300}"
                .getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(verifier.sha256Hex(any())).thenReturn("hash-x");
        when(webhookEvents.findByRoutepayEventId(any())).thenReturn(Optional.empty());
        when(webhookEvents.findFirstByPayloadSha256(any())).thenReturn(Optional.empty());
        when(webhookEvents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Payment pending = pendingPayment("RP-1");
        when(payments.findByRoutepayReference("RP-1")).thenReturn(Optional.of(pending));
        when(payments.save(pending)).thenReturn(pending);

        PaymentService.WebhookResult result = service.handleWebhook(body, "sig");

        assertEquals("PROCESSED", result.reason());
        assertEquals(PaymentStatus.PAID, pending.getStatus());
        assertEquals(300L, pending.getFeeKobo());
        verify(notifyClient).notifyPayment(eq(pending.getTenantId()), eq(pending.getId()),
                eq("PAID"), any(), any(), eq(pending.getAmountKobo()));
    }

    @Test
    void webhookOnAlreadyTerminalPaymentIsIdempotent() {
        byte[] body = "{\"reference\":\"RP-1\",\"status\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(verifier.sha256Hex(any())).thenReturn("hash-y");
        when(webhookEvents.findByRoutepayEventId(any())).thenReturn(Optional.empty());
        when(webhookEvents.findFirstByPayloadSha256(any())).thenReturn(Optional.empty());
        when(webhookEvents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Payment already = pendingPayment("RP-1");
        already.markPaid(java.time.OffsetDateTime.now(), 100L, null);
        when(payments.findByRoutepayReference("RP-1")).thenReturn(Optional.of(already));

        PaymentService.WebhookResult result = service.handleWebhook(body, "sig");

        assertEquals("DUPLICATE", result.reason(),
                "re-applied webhook for a terminal payment must short-circuit without re-saving or re-notifying");
        verify(payments, never()).save(any());
        verify(notifyClient, never()).notifyPayment(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void webhookUnknownReferenceReturnsUnknownReferenceResultButLogsEvent() {
        byte[] body = "{\"reference\":\"RP-ghost\",\"status\":\"PAID\"}".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(verifier.sha256Hex(any())).thenReturn("hash-z");
        when(webhookEvents.findByRoutepayEventId(any())).thenReturn(Optional.empty());
        when(webhookEvents.findFirstByPayloadSha256(any())).thenReturn(Optional.empty());
        when(webhookEvents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payments.findByRoutepayReference("RP-ghost")).thenReturn(Optional.empty());

        PaymentService.WebhookResult result = service.handleWebhook(body, "sig");

        assertEquals("UNKNOWN_REFERENCE", result.reason());
        verify(webhookEvents, times(1)).save(any());
    }

    @Test
    void verifyShortCircuitsForTerminalLocalState() {
        Payment paid = pendingPayment("RP-1");
        paid.markPaid(java.time.OffsetDateTime.now(), 100L, null);
        when(payments.findByRoutepayReference("RP-1")).thenReturn(Optional.of(paid));

        Payment result = service.verify(paid.getTenantId(), "RP-1");

        assertEquals(PaymentStatus.PAID, result.getStatus());
        verify(routePay, never()).getTransaction(any());
    }

    @Test
    void verifyCallsRoutePayAndTerminalisesWhenStillPending() {
        Payment pending = pendingPayment("RP-2");
        when(payments.findByRoutepayReference("RP-2")).thenReturn(Optional.of(pending));
        when(routePay.getTransaction("RP-2")).thenReturn(
                new RoutePayClient.GetTransactionResult("TXN-2", "SUCCESS", 200L, null));
        when(payments.save(pending)).thenReturn(pending);

        Payment result = service.verify(pending.getTenantId(), "RP-2");

        assertEquals(PaymentStatus.PAID, result.getStatus());
        assertEquals(200L, result.getFeeKobo());
    }

    @Test
    void verifyReturnsPendingWhenRoutePayUnavailable() {
        Payment pending = pendingPayment("RP-3");
        when(payments.findByRoutepayReference("RP-3")).thenReturn(Optional.of(pending));
        when(routePay.getTransaction("RP-3"))
                .thenThrow(new RoutePayUnavailableException("502"));

        Payment result = service.verify(pending.getTenantId(), "RP-3");

        assertEquals(PaymentStatus.PENDING, result.getStatus(),
                "verify must not propagate RoutePay outage — the customer just sees the still-PENDING state");
    }

    @Test
    void getByReferenceRefusesCrossTenant() {
        Payment p = pendingPayment("RP-4");
        when(payments.findByRoutepayReference("RP-4")).thenReturn(Optional.of(p));
        UUID otherTenant = UUID.randomUUID();
        assertThrows(NotFoundException.class, () -> service.getByReference(otherTenant, "RP-4"));
    }

    // ----- helpers ------------------------------------------------------------

    private TenantAccount activeAccount() {
        TenantAccount account = new TenantAccount(tenantId, "amaka", "Amaka Styles", "owner@amaka.test");
        account.activate("RP-SUB-1");
        return account;
    }

    private Payment pendingPayment(String reference) {
        Payment p = new Payment(tenantId, "amaka", orderId, null, null,
                "c@x.test", "Cust", "desc", reference, 10_000L);
        setField(Payment.class, p, "id", UUID.randomUUID());
        return p;
    }

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
