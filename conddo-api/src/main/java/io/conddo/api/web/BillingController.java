package io.conddo.api.web;

import io.conddo.api.web.dto.PlanDto;
import io.conddo.api.web.dto.SubscriptionDto;
import io.conddo.api.web.dto.UpgradeRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.service.BillingService;
import io.conddo.core.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Billing endpoints (BILLING_TIERS_SPEC §4). The catalog is public-ish —
 * available to any authenticated user (the FE pricing page can also call it
 * via a server-side request). Subscription read/write is TENANT_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final String ADMIN_ONLY = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";
    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/plans")
    @PreAuthorize(READ)
    public ApiResponse<List<PlanDto>> plans() {
        return ApiResponse.ok(billingService.catalog().stream().map(PlanDto::from).toList());
    }

    @GetMapping("/subscription")
    @PreAuthorize(READ)
    public ApiResponse<SubscriptionDto> subscription() {
        UUID tenantId = TenantContext.require();
        return ApiResponse.ok(billingService.getActiveSubscription(tenantId)
                .map(SubscriptionDto::from)
                .orElseThrow(() -> new NotFoundException("No active subscription for this tenant")));
    }

    @PostMapping("/upgrade")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SubscriptionDto> upgrade(@Valid @RequestBody UpgradeRequest request) {
        UUID tenantId = TenantContext.require();
        billingService.upgrade(tenantId, request.planId(), request.billingCycle());
        // Reread with the plan for the response.
        return ApiResponse.ok(SubscriptionDto.from(
                billingService.getActiveSubscription(tenantId).orElseThrow()));
    }

    @PostMapping("/cancel")
    @PreAuthorize(ADMIN_ONLY)
    public ApiResponse<SubscriptionDto> cancel() {
        UUID tenantId = TenantContext.require();
        billingService.cancel(tenantId);
        return ApiResponse.ok(SubscriptionDto.from(
                billingService.getActiveSubscription(tenantId).orElseThrow()));
    }
}
