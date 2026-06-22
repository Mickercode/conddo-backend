package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.FashionProduct;
import io.conddo.core.repository.FashionProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Fashion-specific product service with size/color/material variant support.
 * Tenant-scoped via RLS.
 */
@Service
public class FashionProductService {

    private final FashionProductRepository fashionProductRepository;
    private final TenantSession tenantSession;

    public FashionProductService(FashionProductRepository fashionProductRepository,
                                  TenantSession tenantSession) {
        this.fashionProductRepository = fashionProductRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Page<FashionProductView> list(String search, String category, String material, 
                                          boolean lowStockOnly, Pageable pageable) {
        tenantSession.bind();
        Page<FashionProduct> page;
        if (search != null && !search.isBlank()) {
            page = fashionProductRepository.findAll(
                fashionProductRepository.search(search), pageable);
        } else if (category != null && !category.isBlank()) {
            page = fashionProductRepository.findAll(
                fashionProductRepository.findByCategory(category), pageable);
        } else if (material != null && !material.isBlank()) {
            page = fashionProductRepository.findAll(
                fashionProductRepository.findByMaterial(material), pageable);
        } else if (lowStockOnly) {
            page = fashionProductRepository.findAll(
                fashionProductRepository.findLowStock(), pageable);
        } else {
            page = fashionProductRepository.findAll(pageable);
        }
        return page.map(FashionProductView::from);
    }

    @Transactional
    public FashionProductView create(String name, String sku, String category, String material,
                                      BigDecimal basePrice, List<FashionProduct.SizeColorVariant> variants) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        FashionProduct product = new FashionProduct(tenantId, name, sku, category, material, basePrice, variants);
        return FashionProductView.from(fashionProductRepository.save(product));
    }

    @Transactional(readOnly = true)
    public FashionProductView get(UUID id) {
        tenantSession.bind();
        FashionProduct product = fashionProductRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion product not found"));
        return FashionProductView.from(product);
    }

    @Transactional
    public FashionProductView update(UUID id, String name, String sku, String category, String material,
                                      BigDecimal basePrice, List<FashionProduct.SizeColorVariant> variants,
                                      Boolean active) {
        tenantSession.bind();
        FashionProduct product = fashionProductRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion product not found"));
        
        if (name != null && !name.isBlank()) {
            product.setName(name);
        }
        if (sku != null) {
            product.setSku(sku);
        }
        if (category != null && !category.isBlank()) {
            product.setCategory(category);
        }
        if (material != null && !material.isBlank()) {
            product.setMaterial(material);
        }
        if (basePrice != null) {
            product.setBasePrice(basePrice);
        }
        if (variants != null) {
            product.setVariants(variants);
        }
        if (active != null) {
            product.setActive(active);
        }
        
        return FashionProductView.from(fashionProductRepository.save(product));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        FashionProduct product = fashionProductRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion product not found"));
        fashionProductRepository.delete(product);
    }

    @Transactional
    public FashionProductView adjustVariantStock(UUID id, String size, String color, int delta) {
        tenantSession.bind();
        FashionProduct product = fashionProductRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion product not found"));
        product.adjustVariantStock(size, color, delta);
        return FashionProductView.from(fashionProductRepository.save(product));
    }

    @Transactional(readOnly = true)
    public List<FashionProductView> lowStock() {
        tenantSession.bind();
        return fashionProductRepository.findLowStock().stream()
            .map(FashionProductView::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public long countLowStock() {
        tenantSession.bind();
        return fashionProductRepository.countLowStock();
    }

    /** View DTO for fashion products. */
    public record FashionProductView(
        UUID id,
        String name,
        String sku,
        String category,
        String material,
        BigDecimal basePrice,
        int totalStock,
        boolean active,
        List<VariantView> variants,
        boolean hasLowStock
    ) {
        static FashionProductView from(FashionProduct p) {
            return new FashionProductView(
                p.getId(),
                p.getName(),
                p.getSku(),
                p.getCategory(),
                p.getMaterial(),
                p.getBasePrice(),
                p.getTotalStock(),
                p.isActive(),
                p.getVariants() != null ? p.getVariants().stream()
                    .map(v -> new VariantView(v.getSize(), v.getColor(), v.getStock()))
                    .toList() : List.of(),
                p.hasLowStock()
            );
        }
    }

    public record VariantView(String size, String color, int stock) {}
}
