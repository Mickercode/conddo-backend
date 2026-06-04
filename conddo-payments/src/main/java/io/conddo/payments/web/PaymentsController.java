package io.conddo.payments.web;

import io.conddo.payments.common.ApiResponse;
import io.conddo.payments.common.TenantPrincipal;
import io.conddo.payments.domain.Payment;
import io.conddo.payments.service.PaymentService;
import io.conddo.payments.web.dto.InitPaymentRequest;
import io.conddo.payments.web.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing payments API. JWT-authenticated; tenant id pulled from the
 * Bearer claim. Three flows:
 * <ul>
 *   <li>{@code POST /charges} — initiate a payment, return checkout URL.</li>
 *   <li>{@code GET /charges/:ref/verify} — refresh status after the customer
 *       returns from the hosted checkout (used while we wait on the webhook).</li>
 *   <li>{@code GET /charges} — paged history for the dashboard.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentsController {

    private static final int MAX_SIZE = 100;

    private final PaymentService paymentService;

    public PaymentsController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/charges")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiate(
            @Valid @RequestBody InitPaymentRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantPrincipal.tenantId(jwt);
        String tenantSlug = jwt.getClaimAsString("tenant_slug");
        Payment created = paymentService.initPayment(tenantId,
                tenantSlug == null ? tenantId.toString() : tenantSlug,
                new PaymentService.InitPaymentInput(
                        body.orderId(), body.bookingId(), body.customerId(),
                        body.customerEmail(), body.customerName(), body.description(),
                        body.returnUrl(), body.amountKobo()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(PaymentResponse.from(created)));
    }

    @GetMapping("/charges/{reference}")
    public ApiResponse<PaymentResponse> get(@PathVariable String reference,
                                            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PaymentResponse.from(
                paymentService.getByReference(TenantPrincipal.tenantId(jwt), reference)));
    }

    @GetMapping("/charges/{reference}/verify")
    public ApiResponse<PaymentResponse> verify(@PathVariable String reference,
                                               @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PaymentResponse.from(
                paymentService.verify(TenantPrincipal.tenantId(jwt), reference)));
    }

    @GetMapping("/charges")
    public ApiResponse<List<PaymentResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @AuthenticationPrincipal Jwt jwt) {
        Page<Payment> result = paymentService.list(TenantPrincipal.tenantId(jwt),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_SIZE)));
        return ApiResponse.ok(
                result.getContent().stream().map(PaymentResponse::from).toList(),
                ApiResponse.Meta.page(result.getNumber(), result.getSize(), result.getTotalElements()));
    }
}
