package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyRefillOffer;
import io.conddo.core.domain.PharmacyRefillOfferClaim;
import io.conddo.core.service.PharmacyRefillOfferService;
import io.conddo.core.service.PharmacyRefillOfferService.IssuedClaim;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped refill offer surface (Pharmacy Spec v2 §12E).
 * The public-side validator lives on PublicRefillOfferController.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/refill-offers")
public class PharmacyRefillOfferController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final PharmacyRefillOfferService service;

    public PharmacyRefillOfferController(PharmacyRefillOfferService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ApiResponse.ok(service.list(activeOnly).stream()
                .map(PharmacyRefillOfferController::toOfferRow)
                .toList());
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody CreateOfferRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        PharmacyRefillOffer created = service.create(body.productId(),
                body.discountType(), body.discountValue(), body.validDays(),
                body.maxUses() == null ? 1 : body.maxUses(), body.message(), createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                Map.of("success", true, "offer", toOfferRow(created))));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> issue(
            @PathVariable UUID id,
            @Valid @RequestBody IssueRequest body) {
        IssuedClaim issued = service.issue(id, body.customerId(),
                Boolean.TRUE.equals(body.sendSms()));
        PharmacyRefillOfferClaim claim = issued.claim();
        Map<String, Object> claimRow = new LinkedHashMap<>();
        claimRow.put("id", claim.getId());
        claimRow.put("offerCode", claim.getOfferCode());
        claimRow.put("expiresAt", claim.getExpiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                Map.of("success", true, "claim", claimRow,
                        "smsRequested", issued.smsRequested())));
    }

    private static Map<String, Object> toOfferRow(PharmacyRefillOffer o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", o.getId());
        row.put("productId", o.getProductId());
        row.put("discountType", o.getDiscountType());
        row.put("discountValue", o.getDiscountValue());
        row.put("validDays", o.getValidDays());
        row.put("maxUses", o.getMaxUses());
        row.put("message", o.getMessage());
        row.put("isActive", o.isActive());
        row.put("createdBy", o.getCreatedBy());
        row.put("createdAt", o.getCreatedAt());
        return row;
    }

    public record CreateOfferRequest(@NotNull UUID productId,
                                     @NotBlank String discountType,
                                     @NotNull @Positive BigDecimal discountValue,
                                     @Positive int validDays,
                                     Integer maxUses,
                                     String message) {
    }

    public record IssueRequest(@NotNull UUID customerId, Boolean sendSms) {
    }
}
