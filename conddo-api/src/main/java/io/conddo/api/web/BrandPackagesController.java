package io.conddo.api.web;

import io.conddo.api.billing.RequiresFeature;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.BrandPackageOffering;
import io.conddo.core.domain.BrandPackageSubscription;
import io.conddo.core.service.BrandPackageService;
import io.conddo.core.service.BrandPackageService.CurrentView;
import io.conddo.core.service.BrandPackageService.SubscribeResult;
import io.conddo.core.service.BrandPackageService.UsageSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-facing brand-package surface
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6). Per §9 the subscription is
 * Growth+, so the {@code @RequiresFeature} gate fires for Launcher
 * tenants on POST. The catalog (GET /offerings) is open to any
 * authenticated tenant — Launcher tenants can browse the prices to see
 * the upgrade incentive.
 */
@RestController
@RequestMapping("/api/v1/brand-packages")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class BrandPackagesController {

    private final BrandPackageService service;

    public BrandPackagesController(BrandPackageService service) {
        this.service = service;
    }

    @GetMapping("/offerings")
    public ApiResponse<List<OfferingResponse>> offerings() {
        return ApiResponse.ok(service.catalog().stream()
                .map(BrandPackagesController::toOfferingResponse)
                .toList());
    }

    @GetMapping("/subscription")
    public ApiResponse<CurrentResponse> subscription() {
        return ApiResponse.ok(service.currentSubscription()
                .map(BrandPackagesController::toCurrentResponse)
                .orElse(new CurrentResponse(null, null)));
    }

    /**
     * Current-period usage counts for the brand-package subscription.
     * Returns {@code data: null} for unsubscribed tenants — the FE
     * renders zero bars when this is null. Earlier (pre-fix) this route
     * crashed with 500 because no handler existed at all.
     */
    @GetMapping("/usage")
    public ApiResponse<UsageResponse> usage() {
        return ApiResponse.ok(service.currentUsage()
                .map(BrandPackagesController::toUsageResponse)
                .orElse(null));
    }

    @PostMapping("/subscription")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')")
    @RequiresFeature("brand_package_subscription")
    public ResponseEntity<ApiResponse<SubscribeResponse>> subscribe(
            @Valid @RequestBody SubscribeRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        SubscribeResult result = service.subscribe(userId, body.offeringCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                new SubscribeResponse(toSubscriptionResponse(result.subscription()),
                        toOfferingResponse(result.offering()),
                        result.checkoutUrl())));
    }

    @PostMapping("/subscription/cancel")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')")
    public ApiResponse<SubscriptionResponse> cancel() {
        BrandPackageSubscription cancelled = service.cancel();
        return ApiResponse.ok(toSubscriptionResponse(cancelled));
    }

    // ----- DTOs --------------------------------------------------------------

    public record SubscribeRequest(@NotBlank String offeringCode) {
    }

    public record SubscribeResponse(SubscriptionResponse subscription,
                                    OfferingResponse offering, String checkoutUrl) {
    }

    public record CurrentResponse(SubscriptionResponse subscription, OfferingResponse offering) {
    }

    public record UsageResponse(Map<String, Integer> counts,
                                OffsetDateTime periodStart,
                                OffsetDateTime periodEnd) {
    }

    public record OfferingResponse(String code, String name, String description,
                                   int monthlyPriceKobo, Map<String, Integer> includes) {
    }

    public record SubscriptionResponse(UUID id, String status,
                                       OffsetDateTime currentPeriodStart, OffsetDateTime currentPeriodEnd,
                                       String paymentReference, OffsetDateTime cancelledAt,
                                       OffsetDateTime createdAt) {
    }

    private static OfferingResponse toOfferingResponse(BrandPackageOffering o) {
        if (o == null) {
            return null;
        }
        return new OfferingResponse(o.getCode(), o.getName(), o.getDescription(),
                o.getMonthlyPriceKobo(), o.getIncludes());
    }

    private static SubscriptionResponse toSubscriptionResponse(BrandPackageSubscription s) {
        return new SubscriptionResponse(s.getId(), s.getStatus(),
                s.getCurrentPeriodStart(), s.getCurrentPeriodEnd(),
                s.getPaymentReference(), s.getCancelledAt(), s.getCreatedAt());
    }

    private static CurrentResponse toCurrentResponse(CurrentView view) {
        return new CurrentResponse(
                toSubscriptionResponse(view.subscription()),
                toOfferingResponse(view.offering()));
    }

    private static UsageResponse toUsageResponse(UsageSnapshot snap) {
        return new UsageResponse(snap.counts(), snap.periodStart(), snap.periodEnd());
    }
}
