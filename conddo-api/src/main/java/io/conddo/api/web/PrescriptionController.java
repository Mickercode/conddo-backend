package io.conddo.api.web;

import io.conddo.api.web.dto.CreatePrescriptionRequest;
import io.conddo.api.web.dto.PrescriptionRemindRequest;
import io.conddo.api.web.dto.PrescriptionSummary;
import io.conddo.api.web.dto.PrescriptionView;
import io.conddo.api.web.dto.UpdatePrescriptionRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.PrescriptionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped pharmacy prescriptions (PHARMACY_DEEP_DIVE_SPEC §3). Wire shape
 * exactly matches {@code conddo-app/lib/api/prescriptions.ts} — field names +
 * types are FE-binding.
 *
 * <p>Reads + non-destructive writes are open to STAFF; DELETE is TENANT_ADMIN
 * only per the spec.
 */
@RestController
@RequestMapping("/api/v1/prescriptions")
public class PrescriptionController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String ADMIN_ONLY = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";
    private static final int MAX_PAGE_SIZE = 100;

    private final PrescriptionService service;

    public PrescriptionController(PrescriptionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<PrescriptionView>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<PrescriptionService.PrescriptionView> p = service.list(search, status, customerId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));
        return ApiResponse.ok(
                p.getContent().stream().map(PrescriptionView::from).toList(),
                new ApiResponse.Meta(p.getNumber(), p.getSize(), p.getTotalElements()));
    }

    @GetMapping("/summary")
    @PreAuthorize(READ)
    public ApiResponse<PrescriptionSummary> summary() {
        return ApiResponse.ok(PrescriptionSummary.from(service.summary()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<PrescriptionView> get(@PathVariable UUID id) {
        return ApiResponse.ok(PrescriptionView.from(service.get(id)));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<PrescriptionView>> create(
            @Valid @RequestBody CreatePrescriptionRequest request) {
        PrescriptionView body = PrescriptionView.from(service.create(
                request.customerId(), request.customerName(), request.customerPhone(),
                request.medication(), request.dosage(), request.quantity(),
                request.refillIntervalDays(), request.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<PrescriptionView> update(@PathVariable UUID id,
                                                @RequestBody UpdatePrescriptionRequest request) {
        return ApiResponse.ok(PrescriptionView.from(service.update(id,
                request.medication(), request.dosage(), request.quantity(),
                request.refillIntervalDays(), request.notes(),
                request.refillIntervalKeyPresent())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMIN_ONLY)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fill")
    @PreAuthorize(WRITE)
    public ApiResponse<PrescriptionView> fill(@PathVariable UUID id) {
        return ApiResponse.ok(PrescriptionView.from(service.fill(id)));
    }

    @PostMapping("/{id}/remind")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> remind(@PathVariable UUID id,
                                       @RequestBody(required = false) PrescriptionRemindRequest request) {
        service.remind(id, request == null ? null : request.message());
        return ResponseEntity.noContent().build();
    }
}
