package io.conddo.api.web;

import io.conddo.api.web.dto.FashionOrderDto;
import io.conddo.api.web.dto.FashionOrderRequest;
import io.conddo.api.web.dto.FashionOrderItemDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.FashionOrder;
import io.conddo.core.service.FashionOrderService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Fashion-specific order controller (Fashion vertical). Handles shoe orders
 * with size/color selection. Tenant-scoped via RLS.
 */
@RestController
@RequestMapping("/api/v1/fashion/orders")
public class FashionOrderController {

    private static final String READ = "@staffAccess.canRead('orders')";
    private static final String WRITE = "@staffAccess.canWrite('orders')";

    private final FashionOrderService fashionOrderService;

    public FashionOrderController(FashionOrderService fashionOrderService) {
        this.fashionOrderService = fashionOrderService;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<FashionOrderDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FashionOrderService.FashionOrderView> result = fashionOrderService.list(
                search, stage, PageRequest.of(page, size));
        List<FashionOrderDto> rows = result.getContent().stream().map(FashionOrderDto::from).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<FashionOrderDto>> create(@Valid @RequestBody FashionOrderRequest request) {
        FashionOrderService.FashionOrderView created = fashionOrderService.create(
                request.reference(), request.customerId(), request.customerName(),
                request.stage(), toOrderItems(request.items()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(FashionOrderDto.from(created)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<FashionOrderDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(FashionOrderDto.from(fashionOrderService.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<FashionOrderDto> update(@PathVariable UUID id, @Valid @RequestBody FashionOrderRequest request) {
        FashionOrderService.FashionOrderView updated = fashionOrderService.update(
                id, request.stage(), request.expectedDelivery(), request.notes(), request.flag());
        return ApiResponse.ok(FashionOrderDto.from(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        fashionOrderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private List<FashionOrder.FashionOrderItem> toOrderItems(List<FashionOrderItemDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(d -> new FashionOrder.FashionOrderItem(
                d.shoeId(), d.shoeName(), d.size(), d.color(), d.quantity(), d.unitPrice())).toList();
    }
}
