package io.conddo.payments.web;

import io.conddo.payments.common.ApiResponse;
import io.conddo.payments.service.PaymentService;
import io.conddo.payments.web.dto.ProvisionTenantRequest;
import io.conddo.payments.web.dto.TenantAccountResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service surface. Authenticated by
 * {@link io.conddo.payments.auth.PaymentsServiceTokenFilter} via the shared
 * {@code X-Payments-Service-Token} header — conddo-api uses this to provision
 * a tenant's RoutePay sub-account on signup.
 *
 * <p>Idempotent: re-calling {@code /tenants} for an already-provisioned tenant
 * returns the existing record (200). Use this as the manual-recovery path
 * when the {@code TenantActivatedEvent} listener missed the call due to a
 * RoutePay outage at signup time.
 */
@RestController
@RequestMapping("/api/payments/internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalController {

    private final PaymentService paymentService;

    public InternalController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/tenants")
    public ResponseEntity<ApiResponse<TenantAccountResponse>> provisionTenant(
            @Valid @RequestBody ProvisionTenantRequest request) {
        TenantAccountResponse body = TenantAccountResponse.from(
                paymentService.provisionTenantAccount(request.tenantId(), request.tenantSlug(),
                        request.businessName(), request.contactEmail()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }
}
