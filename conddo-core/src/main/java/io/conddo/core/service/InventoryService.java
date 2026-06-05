package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.ProductCategory;
import io.conddo.core.domain.StockAdjustment;
import io.conddo.core.repository.ProductCategoryRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.StockAdjustmentRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-scoped inventory (§11.6): products, categories, and stock adjustments.
 * Every method binds the tenant first so RLS scopes all reads/writes. Low-stock
 * (a positive reorder threshold reached by stock) also feeds the dashboard KPI.
 */
@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final StockAdjustmentRepository adjustmentRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public InventoryService(ProductRepository productRepository, ProductCategoryRepository categoryRepository,
                            StockAdjustmentRepository adjustmentRepository, TenantSession tenantSession,
                            Clock clock) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- products -----------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ProductView> list(String search, UUID categoryId, boolean lowStock,
                                  Integer expiringWithinDays, Pageable pageable) {
        tenantSession.bind();
        Page<Product> page = productRepository.findAll(
                filteredBy(search, categoryId, lowStock, expiringWithinDays), pageable);
        Map<UUID, String> categories = categoryNames();
        return page.map(p -> new ProductView(p, categories.get(p.getCategoryId())));
    }

    /** Existing call signature — preserved so legacy callers (seeder) don't break. */
    @Transactional(readOnly = true)
    public Page<ProductView> list(String search, UUID categoryId, boolean lowStock, Pageable pageable) {
        return list(search, categoryId, lowStock, null, pageable);
    }

    @Transactional
    public ProductView create(String name, String sku, UUID categoryId, BigDecimal price,
                              int stock, int reorderThreshold, Boolean active) {
        return create(name, sku, categoryId, price, stock, reorderThreshold, active, null, null);
    }

    /** Full-fidelity create — pharmacy callers pass {@code expiryDate} + {@code batchNumber}. */
    @Transactional
    public ProductView create(String name, String sku, UUID categoryId, BigDecimal price,
                              int stock, int reorderThreshold, Boolean active,
                              LocalDate expiryDate, String batchNumber) {
        tenantSession.bind();
        if (categoryId != null) {
            requireCategory(categoryId);
        }
        Product product = new Product(TenantContext.require(), name, sku, categoryId, price, stock, reorderThreshold);
        if (active != null) {
            product.setActive(active);
        }
        product.setExpiryDate(expiryDate);
        if (batchNumber != null) {
            product.setBatchNumber(batchNumber);
        }
        return withCategory(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public ProductView get(UUID id) {
        tenantSession.bind();
        return withCategory(requireProduct(id));
    }

    @Transactional
    public ProductView update(UUID id, String name, String sku, UUID categoryId, BigDecimal price,
                              Integer stock, Integer reorderThreshold, Boolean active) {
        return update(id, name, sku, categoryId, price, stock, reorderThreshold, active,
                false, null, null);
    }

    /**
     * Full-fidelity PATCH — pharmacy callers can set or clear {@code expiryDate}
     * (clear by passing {@code expiryDateProvided = true} + {@code expiryDate = null}).
     */
    @Transactional
    public ProductView update(UUID id, String name, String sku, UUID categoryId, BigDecimal price,
                              Integer stock, Integer reorderThreshold, Boolean active,
                              boolean expiryDateProvided, LocalDate expiryDate, String batchNumber) {
        tenantSession.bind();
        Product product = requireProduct(id);
        product.rename(name);
        if (sku != null) {
            product.setSku(sku);
        }
        if (categoryId != null) {
            requireCategory(categoryId);
            product.setCategoryId(categoryId);
        }
        if (price != null) {
            product.setPrice(price);
        }
        if (stock != null) {
            product.setStock(stock);
        }
        if (reorderThreshold != null) {
            product.setReorderThreshold(reorderThreshold);
        }
        if (active != null) {
            product.setActive(active);
        }
        if (expiryDateProvided) {
            product.setExpiryDate(expiryDate);
        }
        if (batchNumber != null) {
            product.setBatchNumber(batchNumber);
        }
        return withCategory(productRepository.save(product));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        productRepository.delete(requireProduct(id));
    }

    @Transactional
    public ProductView adjustStock(UUID id, int delta, String reason) {
        tenantSession.bind();
        Product product = requireProduct(id);
        product.adjustStock(delta);
        productRepository.save(product);
        adjustmentRepository.save(new StockAdjustment(TenantContext.require(), id, delta, reason));
        return withCategory(product);
    }

    @Transactional(readOnly = true)
    public List<ProductView> lowStock() {
        tenantSession.bind();
        Map<UUID, String> categories = categoryNames();
        List<ProductView> views = new ArrayList<>();
        for (Product p : productRepository.findLowStock()) {
            views.add(new ProductView(p, categories.get(p.getCategoryId())));
        }
        return views;
    }

    // ----- categories ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProductCategory> categories() {
        tenantSession.bind();
        return categoryRepository.findAllByOrderByName();
    }

    @Transactional
    public ProductCategory createCategory(String name) {
        tenantSession.bind();
        if (categoryRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Category already exists: " + name);
        }
        return categoryRepository.save(new ProductCategory(TenantContext.require(), name));
    }

    // ----- internals ----------------------------------------------------------

    private Map<UUID, String> categoryNames() {
        return categoryRepository.findAllByOrderByName().stream()
                .collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getName));
    }

    private ProductView withCategory(Product product) {
        String name = product.getCategoryId() == null ? null
                : categoryRepository.findById(product.getCategoryId()).map(ProductCategory::getName).orElse(null);
        return new ProductView(product, name);
    }

    private Product requireProduct(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    private ProductCategory requireCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found"));
    }

    private Specification<Product> filteredBy(String search, UUID categoryId, boolean lowStock,
                                              Integer expiringWithinDays) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("sku"), "")), like)));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (lowStock) {
                predicates.add(cb.and(
                        cb.greaterThan(root.get("reorderThreshold"), 0),
                        cb.lessThanOrEqualTo(root.get("stock"), root.get("reorderThreshold"))));
            }
            if (expiringWithinDays != null && expiringWithinDays >= 0) {
                // Includes already-expired (expiry_date <= today + N days).
                LocalDate cutoff = LocalDate.now(clock).plusDays(expiringWithinDays);
                predicates.add(cb.isNotNull(root.get("expiryDate")));
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("expiryDate"), cutoff));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** A product plus its resolved category name (null when uncategorised). */
    public record ProductView(Product product, String categoryName) {
    }
}
