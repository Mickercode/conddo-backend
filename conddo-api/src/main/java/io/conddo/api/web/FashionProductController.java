package io.conddo.api.web;

import io.conddo.api.web.dto.FashionProductDto;
import io.conddo.api.web.dto.FashionProductRequest;
import io.conddo.api.web.dto.VariantDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.FashionProduct;
import io.conddo.core.service.FashionProductService;
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
 * Fashion-specific product controller (Fashion vertical). Handles shoe products
 * with size/color/material variants. Tenant-scoped via RLS.
 */
@RestController
@RequestMapping("/api/v1/fashion/products")
public class FashionProductController {

    private static final String READ = "@staffAccess.canRead('inventory')";
    private static final String WRITE = "@staffAccess.canWrite('inventory')";

    private final FashionProductService fashionProductService;

    public FashionProductController(FashionProductService fashionProductService) {
        this.fashionProductService = fashionProductService;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<FashionProductDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String material,
            @RequestParam(required = false, defaultValue = "false") boolean lowStockOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FashionProductService.FashionProductView> result = fashionProductService.list(
                search, category, material, lowStockOnly, PageRequest.of(page, size));
        List<FashionProductDto> rows = result.getContent().stream().map(FashionProductDto::from).toList();
        return ApiResponse.ok(rows, ApiResponse.Meta.page(
                result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<FashionProductDto>> create(@Valid @RequestBody FashionProductRequest request) {
        FashionProductService.FashionProductView created = fashionProductService.create(
                request.name(), request.sku(), request.category(), request.material(),
                request.basePrice(), toVariants(request.variants()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(FashionProductDto.from(created)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<FashionProductDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(FashionProductDto.from(fashionProductService.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<FashionProductDto> update(@PathVariable UUID id, @Valid @RequestBody FashionProductRequest request) {
        FashionProductService.FashionProductView updated = fashionProductService.update(
                id, request.name(), request.sku(), request.category(), request.material(),
                request.basePrice(), toVariants(request.variants()), request.active());
        return ApiResponse.ok(FashionProductDto.from(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        fashionProductService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/adjust-variant")
    @PreAuthorize(WRITE)
    public ApiResponse<FashionProductDto> adjustVariantStock(@PathVariable UUID id,
                                                               @RequestBody AdjustVariantRequest request) {
        FashionProductService.FashionProductView updated = fashionProductService.adjustVariantStock(
                id, request.size(), request.color(), request.delta());
        return ApiResponse.ok(FashionProductDto.from(updated));
    }

    @GetMapping("/low-stock")
    @PreAuthorize(READ)
    public ApiResponse<List<FashionProductDto>> lowStock() {
        return ApiResponse.ok(fashionProductService.lowStock().stream()
                .map(FashionProductDto::from).toList());
    }

    private List<FashionProduct.SizeColorVariant> toVariants(List<VariantDto> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(d -> new FashionProduct.SizeColorVariant(d.size(), d.color(), d.stock())).toList();
    }

    public record AdjustVariantRequest(String size, String color, int delta) {}
}
