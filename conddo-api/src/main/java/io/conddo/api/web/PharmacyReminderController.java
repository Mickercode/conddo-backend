package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyReminder;
import io.conddo.core.service.PharmacyReminderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped pharmacy reminder surface (Pharmacy Spec v2 §12D).
 * The hourly scheduler that actually dispatches the SMS lives in
 * {@code PharmacyReminderScheduler} on the API side.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/reminders")
public class PharmacyReminderController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";

    private final PharmacyReminderService service;

    public PharmacyReminderController(PharmacyReminderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String reminderType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PharmacyReminder> result = service.list(customerId, reminderType, status,
                PageRequest.of(page, size));
        List<Map<String, Object>> rows = result.getContent().stream()
                .map(PharmacyReminderController::toRow).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody CreateReminderRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        PharmacyReminder created = service.create(body.customerId(), body.productId(),
                body.reminderType(), body.message(), body.scheduledAt(),
                body.recurrence(), body.recurrenceEnd(), createdBy);
        Map<String, Object> reminderRow = new LinkedHashMap<>();
        reminderRow.put("id", created.getId());
        reminderRow.put("status", created.getStatus());
        reminderRow.put("scheduledAt", created.getScheduledAt());
        reminderRow.put("nextSendAt", created.getScheduledAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of(
                "success", true, "reminder", reminderRow)));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id) {
        PharmacyReminder cancelled = service.cancel(id);
        return ApiResponse.ok(Map.of(
                "success", true,
                "reminder", toRow(cancelled)));
    }

    private static Map<String, Object> toRow(PharmacyReminder r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("customerId", r.getCustomerId());
        row.put("productId", r.getProductId());
        row.put("reminderType", r.getReminderType());
        row.put("message", r.getMessage());
        row.put("scheduledAt", r.getScheduledAt());
        row.put("recurrence", r.getRecurrence());
        row.put("recurrenceEnd", r.getRecurrenceEnd());
        row.put("status", r.getStatus());
        row.put("sentAt", r.getSentAt());
        row.put("failureReason", r.getFailureReason());
        row.put("createdBy", r.getCreatedBy());
        row.put("createdAt", r.getCreatedAt());
        return row;
    }

    public record CreateReminderRequest(@NotNull UUID customerId,
                                        UUID productId,
                                        @NotBlank String reminderType,
                                        @NotBlank String message,
                                        @NotNull OffsetDateTime scheduledAt,
                                        String recurrence,
                                        OffsetDateTime recurrenceEnd) {
    }
}
