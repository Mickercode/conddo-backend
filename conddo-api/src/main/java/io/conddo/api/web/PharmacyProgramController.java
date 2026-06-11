package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyProgram;
import io.conddo.core.domain.PharmacyProgramEnrollment;
import io.conddo.core.service.PharmacyProgramService;
import io.conddo.core.service.PharmacyProgramService.EnrollResult;
import io.conddo.core.service.PharmacyProgramService.ItemInput;
import io.conddo.core.service.PharmacyProgramService.ItemView;
import io.conddo.core.service.PharmacyProgramService.ProgramInput;
import io.conddo.core.service.PharmacyProgramService.ProgramView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped drug programs surface (Pharmacy Roadmap Beta 3).
 * Feature-gated by {@code drug_programs}.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/programs")
@PreAuthorize("@featureFlagGuard.requiresFlag('drug_programs') "
        + "and hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class PharmacyProgramController {

    private static final String ADMIN = "@featureFlagGuard.requiresFlag('drug_programs') "
            + "and hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final PharmacyProgramService service;

    public PharmacyProgramController(PharmacyProgramService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list().stream()
                .map(PharmacyProgramController::toRow).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable UUID id) {
        return ApiResponse.ok(toRow(service.get(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody ProgramRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                toRow(service.create(body.toServiceInput(), createdBy))));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable UUID id,
                                                    @Valid @RequestBody ProgramRequest body) {
        return ApiResponse.ok(toRow(service.update(id, body.toServiceInput())));
    }

    @PatchMapping("/{id}/publish")
    @PreAuthorize(ADMIN)
    public ApiResponse<Map<String, Object>> publish(@PathVariable UUID id,
                                                     @Valid @RequestBody PublishRequest body) {
        return ApiResponse.ok(toRow(service.setPublished(id, Boolean.TRUE.equals(body.isPublished()))));
    }

    @GetMapping("/{id}/enrollments")
    public ApiResponse<List<Map<String, Object>>> enrollments(@PathVariable UUID id) {
        return ApiResponse.ok(service.listEnrollments(id).stream()
                .map(PharmacyProgramController::toEnrollmentRow).toList());
    }

    @PostMapping("/{id}/enroll")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enroll(
            @PathVariable UUID id,
            @Valid @RequestBody EnrollRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID enrolledBy = UUID.fromString(jwt.getSubject());
        EnrollResult result = service.enroll(id, body.customerId(), enrolledBy);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enrollment", toEnrollmentRow(result.enrollment()));
        row.put("authorizationUrl", result.authorizationUrl());
        row.put("reference", result.reference());
        row.put("accessCode", result.accessCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(row));
    }

    // ----- shapes ------------------------------------------------------------

    public static Map<String, Object> toRow(ProgramView view) {
        PharmacyProgram p = view.program();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("name", p.getName());
        row.put("description", p.getDescription());
        row.put("targetCondition", p.getTargetCondition());
        row.put("durationMonths", p.getDurationMonths());
        row.put("monthlyPrice", p.getMonthlyPrice());
        row.put("isActive", p.isActive());
        row.put("isPublished", p.isPublished());
        row.put("enrollmentsCount", view.enrollmentsCount());
        row.put("items", view.items().stream()
                .map(PharmacyProgramController::toItemRow).toList());
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("id", p.getCreatedBy());
        row.put("createdBy", by);
        row.put("createdAt", p.getCreatedAt());
        return row;
    }

    private static Map<String, Object> toItemRow(ItemView v) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", v.id());
        row.put("productId", v.productId());
        row.put("productName", v.productName());
        row.put("quantity", v.quantity());
        row.put("frequency", v.frequency());
        return row;
    }

    public static Map<String, Object> toEnrollmentRow(PharmacyProgramEnrollment e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("programId", e.getProgramId());
        row.put("customerId", e.getCustomerId());
        row.put("status", e.getStatus());
        row.put("enrolledAt", e.getEnrolledAt());
        row.put("nextBillingAt", e.getNextBillingAt());
        row.put("endsAt", e.getEndsAt());
        return row;
    }

    // ----- request DTOs ------------------------------------------------------

    public record ProgramRequest(String name, String description, String targetCondition,
                                  Integer durationMonths, BigDecimal monthlyPrice,
                                  Boolean isActive, List<ItemRequest> items) {
        ProgramInput toServiceInput() {
            List<ItemInput> serviceItems = items == null ? null
                    : items.stream().map(i -> new ItemInput(i.productId, i.quantity, i.frequency)).toList();
            return new ProgramInput(name, description, targetCondition,
                    durationMonths, monthlyPrice, isActive, serviceItems);
        }
    }

    public record ItemRequest(@NotNull UUID productId, int quantity, String frequency) {
    }

    public record PublishRequest(Boolean isPublished) {
    }

    public record EnrollRequest(@NotNull UUID customerId) {
    }
}
