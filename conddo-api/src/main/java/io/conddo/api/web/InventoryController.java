package io.conddo.api.web;

import io.conddo.api.web.dto.AdjustStockRequest;
import io.conddo.api.web.dto.CategoryDto;
import io.conddo.api.web.dto.CreateCategoryRequest;
import io.conddo.api.web.dto.CreateProductRequest;
import io.conddo.api.web.dto.ProductRow;
import io.conddo.api.web.dto.UpdateProductRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.InventoryService;
import io.conddo.core.service.InventoryService.ProductView;
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
 * Tenant-scoped inventory (§11.6): products, stock adjustments, low-stock, and
 * categories. Tenant comes from the JWT (RLS). Reads are open to any staff role;
 * writes default to TENANT_ADMIN / SUPER_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/products")
    @PreAuthorize(READ)
    public ApiResponse<List<ProductRow>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID category,
            @RequestParam(required = false, defaultValue = "false") boolean lowStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProductView> result = inventoryService.list(search, category, lowStock, PageRequest.of(page, size));
        List<ProductRow> rows = result.getContent().stream().map(ProductRow::from).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping("/products")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<ProductRow>> create(@Valid @RequestBody CreateProductRequest request) {
        ProductView created = inventoryService.create(request.name(), request.sku(), request.categoryId(),
                request.price(), orZero(request.stock()), orZero(request.reorderThreshold()), request.active());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ProductRow.from(created)));
    }

    @GetMapping("/low-stock")
    @PreAuthorize(READ)
    public ApiResponse<List<ProductRow>> lowStock() {
        return ApiResponse.ok(inventoryService.lowStock().stream().map(ProductRow::from).toList());
    }

    @GetMapping("/categories")
    @PreAuthorize(READ)
    public ApiResponse<List<CategoryDto>> categories() {
        return ApiResponse.ok(inventoryService.categories().stream().map(CategoryDto::from).toList());
    }

    @PostMapping("/categories")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<CategoryDto>> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryDto body = CategoryDto.from(inventoryService.createCategory(request.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/products/{id}")
    @PreAuthorize(READ)
    public ApiResponse<ProductRow> get(@PathVariable UUID id) {
        return ApiResponse.ok(ProductRow.from(inventoryService.get(id)));
    }

    @PatchMapping("/products/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<ProductRow> update(@PathVariable UUID id, @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(ProductRow.from(inventoryService.update(id, request.name(), request.sku(),
                request.categoryId(), request.price(), request.stock(), request.reorderThreshold(), request.active())));
    }

    @DeleteMapping("/products/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        inventoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{id}/adjust")
    @PreAuthorize(WRITE)
    public ApiResponse<ProductRow> adjust(@PathVariable UUID id, @Valid @RequestBody AdjustStockRequest request) {
        return ApiResponse.ok(ProductRow.from(inventoryService.adjustStock(id, request.delta(), request.reason())));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
