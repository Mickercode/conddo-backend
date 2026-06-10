package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyDiscount;
import io.conddo.core.domain.Product;
import io.conddo.core.repository.PharmacyDiscountRepository;
import io.conddo.core.repository.ProductRepository;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Product discount lifecycle (Pharmacy Spec v2 §12B). Staff create
 * discounts in {@code PENDING_APPROVAL}; a tenant admin
 * approves/rejects; once {@code APPROVED}, the discount applies during
 * its time window and is included in the product GET response. The
 * caller is responsible for role checks at the controller layer —
 * this service does not gate by role.
 */
@Service
public class PharmacyDiscountService {

    private final PharmacyDiscountRepository repository;
    private final ProductRepository productRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PharmacyDiscountService(PharmacyDiscountRepository repository,
                                   ProductRepository productRepository,
                                   TenantSession tenantSession,
                                   Clock clock) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional
    public PharmacyDiscount create(UUID productId, String discountType, BigDecimal discountValue,
                                   String label, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                   UUID createdBy) {
        tenantSession.bind();
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }
        if (!PharmacyDiscount.TYPE_PERCENTAGE.equals(discountType)
                && !PharmacyDiscount.TYPE_FIXED.equals(discountType)) {
            throw new IllegalArgumentException("discountType must be PERCENTAGE or FIXED");
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new IllegalArgumentException("discountValue must be > 0");
        }
        if (PharmacyDiscount.TYPE_PERCENTAGE.equals(discountType)
                && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("PERCENTAGE discount cannot exceed 100");
        }
        if (startsAt == null) {
            throw new IllegalArgumentException("startsAt is required");
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        return repository.save(new PharmacyDiscount(TenantContext.require(), productId,
                discountType, discountValue, label, startsAt, endsAt, createdBy));
    }

    @Transactional(readOnly = true)
    public Page<PharmacyDiscount> list(String status, UUID productId, Pageable pageable) {
        tenantSession.bind();
        Specification<PharmacyDiscount> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (productId != null) {
                predicates.add(cb.equal(root.get("productId"), productId));
            }
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public PharmacyDiscount get(UUID id) {
        tenantSession.bind();
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Discount not found"));
    }

    /**
     * Pulls the discount + its product in one tenant-bound transaction
     * so the FE row shape (which includes product price + computed
     * discountedPrice) can be assembled without the controller making
     * a second, non-bound query. RLS-scoped.
     */
    @Transactional(readOnly = true)
    public DiscountWithProduct getWithProduct(UUID id) {
        tenantSession.bind();
        PharmacyDiscount d = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Discount not found"));
        Product p = productRepository.findById(d.getProductId()).orElse(null);
        return new DiscountWithProduct(d, p);
    }

    /**
     * Returns the discount as already-loaded from {@link #approve} /
     * {@link #reject} paired with its product, all in one bound tx.
     * Used by the controller to build its response row.
     */
    @Transactional(readOnly = true)
    public DiscountWithProduct enrich(PharmacyDiscount d) {
        tenantSession.bind();
        Product p = productRepository.findById(d.getProductId()).orElse(null);
        return new DiscountWithProduct(d, p);
    }

    public record DiscountWithProduct(PharmacyDiscount discount, Product product) {
    }

    @Transactional
    public PharmacyDiscount approve(UUID id, UUID approver) {
        tenantSession.bind();
        PharmacyDiscount d = get(id);
        if (!PharmacyDiscount.STATUS_PENDING.equals(d.getStatus())) {
            throw new IllegalArgumentException("Discount is not pending approval");
        }
        d.approve(approver, OffsetDateTime.now(clock));
        return repository.save(d);
    }

    @Transactional
    public PharmacyDiscount reject(UUID id, UUID approver, String note) {
        tenantSession.bind();
        PharmacyDiscount d = get(id);
        if (!PharmacyDiscount.STATUS_PENDING.equals(d.getStatus())) {
            throw new IllegalArgumentException("Discount is not pending approval");
        }
        d.reject(approver, OffsetDateTime.now(clock), note);
        return repository.save(d);
    }

    /**
     * Delete a discount. The controller enforces the spec's rule that
     * only the creator can delete a pending one; an admin can delete
     * anything. We just delete the row here.
     */
    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        PharmacyDiscount d = get(id);
        repository.delete(d);
    }

    /** Used by catalog reads and checkout. RLS-scoped to the bound tenant. */
    @Transactional(readOnly = true)
    public Optional<PharmacyDiscount> activeForProduct(UUID productId) {
        tenantSession.bind();
        return repository.findActiveForProduct(productId, OffsetDateTime.now(clock));
    }

    /**
     * Flip every APPROVED row whose {@code ends_at} has passed to
     * {@code EXPIRED}. Idempotent; safe to run from a scheduled job
     * with no bound tenant — uses the V38 cross_tenant carve-out.
     */
    @Transactional
    public int sweepExpired() {
        tenantSession.bindCrossTenant();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PharmacyDiscount> due = repository.findExpiredButNotYetSwept(now);
        for (PharmacyDiscount d : due) {
            d.markExpired();
            repository.save(d);
        }
        return due.size();
    }
}
