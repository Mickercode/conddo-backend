package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyReconciliationItem;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.service.BulkStockUploadService;
import io.conddo.core.service.PharmacyReconciliationService;
import io.conddo.core.service.PharmacyReconciliationService.CountInput;
import io.conddo.core.service.PharmacyReconciliationService.Loaded;
import io.conddo.core.service.PharmacyReconciliationService.Started;
import io.conddo.core.service.PharmacyReconciliationService.Summary;
import io.conddo.core.service.StockMovementService;
import io.conddo.core.service.StockMovementService.RestockLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped inventory reconciliation surface (Pharmacy Spec v2
 * §12A). Endpoints sit on the existing {@code /api/v1/inventory} prefix
 * — slug is implicit from the JWT, matching the rest of the dashboard
 * (the spec's {@code /dashboard/{slug}/pharmacy/inventory/...} form is
 * a path-prefix style we don't use elsewhere).
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class PharmacyInventoryController {

    // Replaced the role-based gates with module-based gates so the
    // STAFF sub-roles (STOCK_MANAGER + MANAGER write; CASHIER +
    // PHARMACIST + BOOKKEEPER read-only) are enforced consistently.
    private static final String READ = "@staffAccess.canRead('inventory')";
    private static final String WRITE = "@staffAccess.canWrite('inventory')";

    private final StockMovementService movementService;
    private final PharmacyReconciliationService reconciliationService;
    private final BulkStockUploadService bulkUploadService;

    public PharmacyInventoryController(StockMovementService movementService,
                                       PharmacyReconciliationService reconciliationService,
                                       BulkStockUploadService bulkUploadService) {
        this.movementService = movementService;
        this.reconciliationService = reconciliationService;
        this.bulkUploadService = bulkUploadService;
    }

    /**
     * Ground-truth stock upload. Send the CSV as multipart field
     * {@code file}; pass {@code dryRun=true} to get the summary
     * without persisting (preview before commit). See
     * {@link BulkStockUploadService} for the column contract.
     */
    @PostMapping("/bulk-upload")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> bulkUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        UUID actingUserId = UUID.fromString(jwt.getSubject());
        BulkStockUploadService.Summary summary = bulkUploadService.upload(
                file.getInputStream(), dryRun, actingUserId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dryRun", summary.dryRun());
        resp.put("totalRows", summary.totalRows());
        resp.put("created", summary.created());
        resp.put("updated", summary.updated());
        resp.put("skipped", summary.skipped());
        resp.put("errors", summary.errors());
        resp.put("preview", summary.preview());
        return ApiResponse.ok(resp);
    }

    @GetMapping("/movements")
    @PreAuthorize(READ)
    public ApiResponse<List<Map<String, Object>>> movements(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StockMovement> result = movementService.list(productId, movementType, from, to,
                PageRequest.of(page, size));
        List<Map<String, Object>> rows = result.getContent().stream()
                .map(PharmacyInventoryController::toMovementRow)
                .toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping("/restock")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> restock(
            @Valid @RequestBody RestockRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        List<RestockLine> lines = body.items().stream()
                .map(i -> new RestockLine(i.productId(), i.quantity()))
                .toList();
        List<StockMovement> movements = movementService.restock(lines, body.note(), createdBy);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("restockId", movements.get(0).getReferenceId());
        resp.put("itemsRestocked", movements.size());
        resp.put("movements", movements.stream().map(PharmacyInventoryController::toMovementRow).toList());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp));
    }

    @PostMapping("/adjustment")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> adjustment(@Valid @RequestBody AdjustmentRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        StockMovement m = movementService.setAbsolute(body.productId(), body.adjustedQty(),
                body.reason(), body.note(), createdBy);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("movement", toMovementRow(m));
        resp.put("quantityBefore", m.getQuantityBefore());
        resp.put("quantityAfter", m.getQuantityAfter());
        resp.put("variance", m.getQuantityChange());
        return ApiResponse.ok(resp);
    }

    @PostMapping("/reconciliations")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> startReconciliation(
            @RequestBody(required = false) StartReconciliationRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID startedBy = UUID.fromString(jwt.getSubject());
        String note = body == null ? null : body.note();
        Started started = reconciliationService.start(startedBy, note);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("reconciliationId", started.session().getId());
        resp.put("status", started.session().getStatus());
        resp.put("totalProducts", started.totalProducts());
        resp.put("startedAt", started.session().getStartedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(resp));
    }

    @GetMapping("/reconciliations/{id}")
    @PreAuthorize(READ)
    public ApiResponse<Map<String, Object>> getReconciliation(@PathVariable UUID id) {
        return ApiResponse.ok(toReconciliation(reconciliationService.get(id)));
    }

    @PatchMapping("/reconciliations/{id}/counts")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> submitCounts(@PathVariable UUID id,
                                                         @Valid @RequestBody SubmitCountsRequest body) {
        List<CountInput> counts = body.counts().stream()
                .map(c -> new CountInput(c.productId(), c.countedQty()))
                .toList();
        return ApiResponse.ok(toReconciliation(reconciliationService.submitCounts(id, counts)));
    }

    @PostMapping("/reconciliations/{id}/complete")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> completeReconciliation(@PathVariable UUID id,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        UUID completedBy = UUID.fromString(jwt.getSubject());
        Summary summary = reconciliationService.complete(id, completedBy);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("completedAt", OffsetDateTime.now());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalProducts", summary.totalProducts());
        s.put("matched", summary.matched());
        s.put("variance", summary.variance());
        s.put("totalVarianceUnits", summary.totalVarianceUnits());
        s.put("adjustmentsApplied", summary.adjustmentsApplied());
        resp.put("summary", s);
        return ApiResponse.ok(resp);
    }

    // ----- shapes ------------------------------------------------------------

    private static Map<String, Object> toMovementRow(StockMovement m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("productId", m.getProductId());
        row.put("movementType", m.getMovementType());
        row.put("quantityChange", m.getQuantityChange());
        row.put("quantityBefore", m.getQuantityBefore());
        row.put("quantityAfter", m.getQuantityAfter());
        row.put("referenceId", m.getReferenceId());
        row.put("referenceKind", m.getReferenceKind());
        row.put("note", m.getNote());
        row.put("createdBy", m.getCreatedBy());
        row.put("createdAt", m.getCreatedAt());
        return row;
    }

    private static Map<String, Object> toReconciliation(Loaded loaded) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", loaded.session().getId());
        session.put("status", loaded.session().getStatus());
        session.put("startedAt", loaded.session().getStartedAt());
        session.put("completedAt", loaded.session().getCompletedAt());
        session.put("startedBy", loaded.session().getStartedBy());
        session.put("completedBy", loaded.session().getCompletedBy());
        session.put("notes", loaded.session().getNotes());
        session.put("items", loaded.items().stream()
                .map(PharmacyInventoryController::toReconciliationItem)
                .toList());
        return Map.of("reconciliation", session);
    }

    private static Map<String, Object> toReconciliationItem(PharmacyReconciliationItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", item.getProductId());
        row.put("systemQty", item.getSystemQty());
        row.put("countedQty", item.getCountedQty());
        row.put("variance", item.getVariance());
        row.put("resolved", item.isResolved());
        return row;
    }

    // ----- request DTOs ------------------------------------------------------

    public record RestockRequest(@NotEmpty List<RestockItem> items, String note) {
        public record RestockItem(@NotNull UUID productId, @Positive int quantity) {
        }
    }

    public record AdjustmentRequest(@NotNull UUID productId,
                                    @PositiveOrZero int adjustedQty,
                                    String reason,
                                    String note) {
    }

    public record StartReconciliationRequest(String note) {
    }

    public record SubmitCountsRequest(@NotEmpty List<CountRow> counts) {
        public record CountRow(@NotNull UUID productId, @PositiveOrZero int countedQty) {
        }
    }
}
