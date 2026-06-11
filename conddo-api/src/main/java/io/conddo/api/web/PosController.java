package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.PosPayment;
import io.conddo.core.domain.PosSale;
import io.conddo.core.domain.PosSaleItem;
import io.conddo.core.domain.PosSession;
import io.conddo.core.service.PosSaleService;
import io.conddo.core.service.PosSaleService.PickerResult;
import io.conddo.core.service.PosSaleService.Receipt;
import io.conddo.core.service.PosSessionService;
import io.conddo.core.service.PosSessionService.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Point-of-Sale surface (Phase 1). Feature-gated by {@code pos};
 * roles STAFF + TENANT_ADMIN run sales, only TENANT_ADMIN can void.
 *
 * <p>All shapes follow FE_HANDOFF_POS_PHASE1.md.
 */
@RestController
@RequestMapping("/api/v1/pos")
@PreAuthorize("@featureFlagGuard.requiresFlag('pos') "
        + "and hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class PosController {

    private static final String VOID_AUTH = "@featureFlagGuard.requiresFlag('pos') "
            + "and hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final PosSessionService sessionService;
    private final PosSaleService saleService;

    public PosController(PosSessionService sessionService, PosSaleService saleService) {
        this.sessionService = sessionService;
        this.saleService = saleService;
    }

    // ----- sessions ----------------------------------------------------------

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openSession(
            @Valid @RequestBody OpenSessionRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID cashierId = UUID.fromString(jwt.getSubject());
        PosSession session = sessionService.open(cashierId, body.openingFloat(), body.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toSessionRow(
                new PosSessionService.View(session,
                        new Summary(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))));
    }

    @GetMapping("/sessions/current")
    public ApiResponse<Map<String, Object>> currentSession(@AuthenticationPrincipal Jwt jwt) {
        UUID cashierId = UUID.fromString(jwt.getSubject());
        Optional<PosSessionService.View> current = sessionService.findCurrent(cashierId);
        return current.map(v -> ApiResponse.ok(toSessionRow(v)))
                .orElseGet(() -> ApiResponse.ok(null));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable UUID id) {
        return ApiResponse.ok(toSessionRow(sessionService.get(id)));
    }

    @PostMapping("/sessions/{id}/close")
    public ApiResponse<Map<String, Object>> closeSession(@PathVariable UUID id,
                                                         @Valid @RequestBody CloseSessionRequest body) {
        return ApiResponse.ok(toSessionRow(
                sessionService.close(id, body.countedCash(), body.notes())));
    }

    // ----- sales -------------------------------------------------------------

    @PostMapping("/sales")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openSale(
            @RequestBody(required = false) OpenSaleRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID cashierId = UUID.fromString(jwt.getSubject());
        UUID customerId = body == null ? null : body.customerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(toSaleRow(saleService.openSale(cashierId, customerId), null, null)));
    }

    @GetMapping("/sales/{id}")
    public ApiResponse<Map<String, Object>> getSale(@PathVariable UUID id) {
        return ApiResponse.ok(toSaleRow(saleService.get(id), null, null));
    }

    @PostMapping("/sales/{id}/items")
    public ApiResponse<Map<String, Object>> addItem(@PathVariable UUID id,
                                                    @Valid @RequestBody AddItemRequest body) {
        return ApiResponse.ok(toSaleRow(saleService.addItem(id, body.productId(), body.qty()), null, null));
    }

    @PatchMapping("/sales/{id}/items/{itemId}")
    public ApiResponse<Map<String, Object>> updateItem(@PathVariable UUID id,
                                                       @PathVariable UUID itemId,
                                                       @Valid @RequestBody UpdateItemRequest body) {
        return ApiResponse.ok(toSaleRow(saleService.updateItemQty(id, itemId, body.qty()), null, null));
    }

    @DeleteMapping("/sales/{id}/items/{itemId}")
    public ApiResponse<Map<String, Object>> removeItem(@PathVariable UUID id,
                                                       @PathVariable UUID itemId) {
        return ApiResponse.ok(toSaleRow(saleService.removeItem(id, itemId), null, null));
    }

    @PostMapping("/sales/{id}/attach-customer")
    public ApiResponse<Map<String, Object>> attachCustomer(@PathVariable UUID id,
                                                            @Valid @RequestBody AttachCustomerRequest body) {
        return ApiResponse.ok(toSaleRow(saleService.attachCustomer(id, body.customerId()), null, null));
    }

    @PostMapping("/sales/{id}/payments")
    public ApiResponse<Map<String, Object>> addPayment(@PathVariable UUID id,
                                                       @Valid @RequestBody AddPaymentRequest body) {
        return ApiResponse.ok(toSaleRow(
                saleService.addPayment(id, body.method(), body.amount(), body.reference()), null, null));
    }

    @DeleteMapping("/sales/{id}/payments/{paymentId}")
    public ApiResponse<Map<String, Object>> removePayment(@PathVariable UUID id,
                                                           @PathVariable UUID paymentId) {
        return ApiResponse.ok(toSaleRow(saleService.removePayment(id, paymentId), null, null));
    }

    @PostMapping("/sales/{id}/complete")
    public ApiResponse<Map<String, Object>> complete(@PathVariable UUID id,
                                                     @AuthenticationPrincipal Jwt jwt) {
        UUID cashierId = UUID.fromString(jwt.getSubject());
        Receipt receipt = saleService.complete(id, cashierId);
        return ApiResponse.ok(toSaleRow(receipt.view(), receipt.loyaltyEarned(), receipt.change()));
    }

    @PostMapping("/sales/{id}/void")
    @PreAuthorize(VOID_AUTH)
    public ApiResponse<Map<String, Object>> voidSale(@PathVariable UUID id) {
        return ApiResponse.ok(toSaleRow(saleService.voidSale(id), null, null));
    }

    // ----- product picker ----------------------------------------------------

    @GetMapping("/products/search")
    public ApiResponse<List<Map<String, Object>>> searchProducts(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(saleService.searchProducts(q, limit).stream()
                .map(PosController::toPickerRow).toList());
    }

    // ----- shapes ------------------------------------------------------------

    private static Map<String, Object> toSessionRow(PosSessionService.View view) {
        PosSession s = view.session();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("cashierId", s.getCashierId());
        row.put("status", s.getStatus());
        row.put("openingFloat", s.getOpeningFloat());
        row.put("countedCash", s.getCountedCash());
        row.put("expectedCash", s.getExpectedCash());
        row.put("cashVariance", s.getCashVariance());
        row.put("notes", s.getNotes());
        row.put("openedAt", s.getOpenedAt());
        row.put("closedAt", s.getClosedAt());
        Summary summary = view.summary();
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("salesCount", summary.salesCount());
        sum.put("totalSales", summary.totalSales());
        sum.put("totalCash", summary.totalCash());
        sum.put("totalTransfer", summary.totalTransfer());
        row.put("summary", sum);
        return row;
    }

    private static Map<String, Object> toSaleRow(PosSaleService.View view,
                                                 BigDecimal loyaltyEarned, BigDecimal change) {
        PosSale s = view.sale();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("saleNumber", s.getSaleNumber());
        row.put("sessionId", s.getSessionId());
        row.put("cashierId", s.getCashierId());
        row.put("customer", toCustomer(view.customer()));
        row.put("status", s.getStatus());
        row.put("items", view.items().stream().map(PosController::toItem).toList());
        row.put("payments", view.payments().stream().map(PosController::toPayment).toList());
        row.put("subtotal", s.getSubtotal());
        row.put("total", s.getTotal());
        row.put("paid", view.paid());
        row.put("balance", view.balance());
        row.put("openedAt", s.getOpenedAt());
        row.put("completedAt", s.getCompletedAt());
        row.put("voidedAt", s.getVoidedAt());
        if (PosSale.STATUS_COMPLETED.equals(s.getStatus())) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("saleNumber", s.getSaleNumber());
            receipt.put("lines", view.items().stream().map(PosController::toItem).toList());
            receipt.put("subtotal", s.getSubtotal());
            receipt.put("total", s.getTotal());
            receipt.put("payments", view.payments().stream().map(PosController::toPayment).toList());
            receipt.put("change", change == null ? BigDecimal.ZERO : change);
            receipt.put("loyaltyEarned", loyaltyEarned == null ? BigDecimal.ZERO : loyaltyEarned);
            receipt.put("completedAt", s.getCompletedAt());
            row.put("receipt", receipt);
        }
        return row;
    }

    private static Map<String, Object> toCustomer(Customer c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("name", c.getFullName());
        row.put("phone", c.getPhone());
        return row;
    }

    private static Map<String, Object> toItem(PosSaleItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("productId", item.getProductId());
        row.put("productName", item.getProductName());
        row.put("sku", item.getSku());
        row.put("qty", item.getQty());
        row.put("unitPrice", item.getUnitPrice());
        row.put("lineTotal", item.getLineTotal());
        return row;
    }

    private static Map<String, Object> toPayment(PosPayment p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("method", p.getMethod());
        row.put("amount", p.getAmount());
        row.put("reference", p.getReference());
        row.put("paidAt", p.getPaidAt());
        return row;
    }

    private static Map<String, Object> toPickerRow(PickerResult r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", r.productId());
        row.put("name", r.name());
        row.put("sku", r.sku());
        row.put("price", r.price());
        row.put("stock", r.stock());
        row.put("lowStock", r.lowStock());
        return row;
    }

    // ----- request DTOs ------------------------------------------------------

    public record OpenSessionRequest(@NotNull @PositiveOrZero BigDecimal openingFloat, String notes) {
    }

    public record CloseSessionRequest(@NotNull @PositiveOrZero BigDecimal countedCash, String notes) {
    }

    public record OpenSaleRequest(UUID customerId) {
    }

    public record AddItemRequest(@NotNull UUID productId, @Positive int qty) {
    }

    public record UpdateItemRequest(@Positive int qty) {
    }

    public record AttachCustomerRequest(UUID customerId) {
    }

    public record AddPaymentRequest(@NotNull String method,
                                    @NotNull @Positive BigDecimal amount,
                                    String reference) {
    }
}
