package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.PosPayment;
import io.conddo.core.domain.PosSale;
import io.conddo.core.domain.PosSaleItem;
import io.conddo.core.domain.PosSession;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PosPaymentRepository;
import io.conddo.core.repository.PosSaleItemRepository;
import io.conddo.core.repository.PosSaleRepository;
import io.conddo.core.repository.PosSessionRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POS sale lifecycle (Phase 1). Carries OPEN sales (cart-building),
 * COMPLETED sales (stock decremented, loyalty credited), and VOIDED
 * sales (no inventory effect). The complete() path is the only place
 * that writes {@link StockMovement.Type#SALE_POS} movements — same
 * chokepoint as every other inventory change, so the existing
 * dashboard, low-stock KPI, and audit-trail surfaces light up
 * automatically.
 */
@Service
public class PosSaleService {

    private static final Logger log = LoggerFactory.getLogger(PosSaleService.class);

    private final PosSessionRepository sessionRepository;
    private final PosSaleRepository saleRepository;
    private final PosSaleItemRepository itemRepository;
    private final PosPaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final StockMovementService movementService;
    private final PharmacyLoyaltyService loyaltyService;
    private final TenantSession tenantSession;
    private final EntityManager entityManager;
    private final Clock clock;

    public PosSaleService(PosSessionRepository sessionRepository,
                          PosSaleRepository saleRepository,
                          PosSaleItemRepository itemRepository,
                          PosPaymentRepository paymentRepository,
                          ProductRepository productRepository,
                          CustomerRepository customerRepository,
                          StockMovementService movementService,
                          PharmacyLoyaltyService loyaltyService,
                          TenantSession tenantSession,
                          EntityManager entityManager,
                          Clock clock) {
        this.sessionRepository = sessionRepository;
        this.saleRepository = saleRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.movementService = movementService;
        this.loyaltyService = loyaltyService;
        this.tenantSession = tenantSession;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ----- sale lifecycle ----------------------------------------------------

    @Transactional
    public View openSale(UUID cashierId, UUID customerId) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        PosSession session = sessionRepository.findFirstByCashierIdAndStatus(
                cashierId, PosSession.STATUS_OPEN)
                .orElseThrow(() -> new NoOpenSessionException());
        if (customerId != null && customerRepository.findById(customerId).isEmpty()) {
            throw new NotFoundException("Customer not found");
        }
        String saleNumber = nextSaleNumber(tenantId);
        PosSale sale = saleRepository.save(new PosSale(tenantId, session.getId(), cashierId,
                customerId, saleNumber, OffsetDateTime.now(clock)));
        return view(sale);
    }

    @Transactional(readOnly = true)
    public View get(UUID saleId) {
        tenantSession.bind();
        return view(loadSale(saleId));
    }

    @Transactional
    public View attachCustomer(UUID saleId, UUID customerId) {
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        if (customerId != null && customerRepository.findById(customerId).isEmpty()) {
            throw new NotFoundException("Customer not found");
        }
        sale.setCustomerId(customerId);
        return view(saleRepository.save(sale));
    }

    @Transactional
    public View addItem(UUID saleId, UUID productId, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));

        PosSaleItem item = itemRepository.findBySaleIdAndProductId(saleId, productId).orElse(null);
        int currentQtyInCart = item == null ? 0 : item.getQty();
        int newQty = currentQtyInCart + qty;
        if (newQty > product.getStock()) {
            throw new InsufficientStockException(productId, product.getStock());
        }
        if (item == null) {
            item = new PosSaleItem(sale.getTenantId(), saleId, productId, product.getName(),
                    product.getSku(), newQty, product.getPrice());
        } else {
            item.setQty(newQty);
        }
        itemRepository.save(item);
        recomputeTotals(sale);
        return view(sale);
    }

    @Transactional
    public View updateItemQty(UUID saleId, UUID itemId, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0; use DELETE to remove the line");
        }
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        PosSaleItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        if (!item.getSaleId().equals(saleId)) {
            throw new NotFoundException("Item not on this sale");
        }
        Product product = productRepository.findById(item.getProductId())
                .orElseThrow(() -> new NotFoundException("Product vanished"));
        if (qty > product.getStock()) {
            throw new InsufficientStockException(item.getProductId(), product.getStock());
        }
        item.setQty(qty);
        itemRepository.save(item);
        recomputeTotals(sale);
        return view(sale);
    }

    @Transactional
    public View removeItem(UUID saleId, UUID itemId) {
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        PosSaleItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        if (!item.getSaleId().equals(saleId)) {
            throw new NotFoundException("Item not on this sale");
        }
        itemRepository.delete(item);
        recomputeTotals(sale);
        return view(sale);
    }

    @Transactional
    public View addPayment(UUID saleId, String method, BigDecimal amount, String reference) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (!PosPayment.METHOD_CASH.equals(method) && !PosPayment.METHOD_TRANSFER.equals(method)) {
            throw new IllegalArgumentException(
                    "method must be CASH or TRANSFER (CARD comes in Phase 2)");
        }
        if (PosPayment.METHOD_TRANSFER.equals(method) && (reference == null || reference.isBlank())) {
            throw new IllegalArgumentException("reference is required for TRANSFER payments");
        }
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        paymentRepository.save(new PosPayment(sale.getTenantId(), saleId, method, amount,
                reference, OffsetDateTime.now(clock)));
        return view(sale);
    }

    @Transactional
    public View removePayment(UUID saleId, UUID paymentId) {
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        PosPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        if (!payment.getSaleId().equals(saleId)) {
            throw new NotFoundException("Payment not on this sale");
        }
        paymentRepository.delete(payment);
        return view(sale);
    }

    @Transactional
    public Receipt complete(UUID saleId, UUID cashierId) {
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        requireOpen(sale);
        List<PosSaleItem> items = itemRepository.findBySaleIdOrderByCreatedAtAsc(saleId);
        if (items.isEmpty()) {
            throw new SaleHasNoItemsException();
        }
        List<PosPayment> payments = paymentRepository.findBySaleIdOrderByPaidAtAsc(saleId);
        BigDecimal paid = sumPayments(payments);
        if (paid.compareTo(sale.getTotal()) < 0) {
            throw new PaymentInsufficientException(sale.getTotal().subtract(paid));
        }
        // Decrement stock per line via the SALE_POS chokepoint.
        for (PosSaleItem item : items) {
            movementService.recordMovement(item.getProductId(), StockMovement.Type.SALE_POS,
                    -item.getQty(), saleId, "POS_SALE",
                    "Sale " + sale.getSaleNumber(), cashierId);
        }
        sale.markCompleted(OffsetDateTime.now(clock));
        saleRepository.save(sale);

        // Loyalty credit (idempotent on saleId — calling twice is a no-op).
        BigDecimal loyaltyEarned = BigDecimal.ZERO;
        if (sale.getCustomerId() != null) {
            try {
                loyaltyEarned = loyaltyService.creditCashback(sale.getCustomerId(), saleId,
                        sale.getTotal())
                        .map(tx -> tx.getAmount())
                        .orElse(BigDecimal.ZERO);
            } catch (RuntimeException ex) {
                // Never fail a sale because loyalty was unhappy.
                log.warn("Loyalty credit failed for sale {}: {}", saleId, ex.getMessage());
            }
        }

        BigDecimal change = paid.subtract(sale.getTotal());
        return new Receipt(view(sale), loyaltyEarned, change);
    }

    @Transactional
    public View voidSale(UUID saleId) {
        tenantSession.bind();
        PosSale sale = loadSale(saleId);
        if (PosSale.STATUS_COMPLETED.equals(sale.getStatus())) {
            throw new SaleAlreadyCompletedException();
        }
        if (PosSale.STATUS_VOIDED.equals(sale.getStatus())) {
            return view(sale);    // idempotent
        }
        sale.markVoided(OffsetDateTime.now(clock));
        saleRepository.save(sale);
        return view(sale);
    }

    // ----- product picker ----------------------------------------------------

    @Transactional(readOnly = true)
    public List<PickerResult> searchProducts(String q, int limit) {
        tenantSession.bind();
        String like = (q == null ? "" : q.trim()).toLowerCase();
        int capped = Math.min(Math.max(limit, 1), 50);
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            if (!like.isEmpty()) {
                Predicate byName = cb.like(cb.lower(root.get("name")), "%" + like + "%");
                Predicate bySku = cb.like(cb.lower(root.get("sku")), like + "%");
                predicates.add(cb.or(byName, bySku));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return productRepository.findAll(spec, PageRequest.of(0, capped,
                        Sort.by(Sort.Direction.DESC, "stock").and(Sort.by("name"))))
                .stream()
                .map(p -> new PickerResult(p.getId(), p.getName(), p.getSku(),
                        p.getPrice(), p.getStock(), p.isLowStock()))
                .toList();
    }

    // ----- internals ---------------------------------------------------------

    private PosSale loadSale(UUID saleId) {
        return saleRepository.findById(saleId)
                .orElseThrow(() -> new NotFoundException("Sale not found"));
    }

    private static void requireOpen(PosSale sale) {
        if (!PosSale.STATUS_OPEN.equals(sale.getStatus())) {
            throw new SaleNotOpenException(sale.getStatus());
        }
    }

    private void recomputeTotals(PosSale sale) {
        List<PosSaleItem> items = itemRepository.findBySaleIdOrderByCreatedAtAsc(sale.getId());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PosSaleItem item : items) {
            subtotal = subtotal.add(item.getLineTotal());
        }
        sale.recomputeTotals(subtotal);
        saleRepository.save(sale);
    }

    private static BigDecimal sumPayments(List<PosPayment> payments) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PosPayment p : payments) {
            sum = sum.add(p.getAmount());
        }
        return sum;
    }

    /**
     * Atomic per-day, per-tenant sale-number sequence. Uses
     * {@code INSERT ... ON CONFLICT ... RETURNING} so concurrent
     * cashiers never collide on the same number.
     */
    @SuppressWarnings("unchecked")
    private String nextSaleNumber(UUID tenantId) {
        LocalDate today = LocalDate.now(clock);
        Object result = entityManager.createNativeQuery(
                        "INSERT INTO pos_sale_counters (tenant_id, sale_date, next_seq) "
                                + "VALUES (CAST(:t AS uuid), :d, 1) "
                                + "ON CONFLICT (tenant_id, sale_date) "
                                + "DO UPDATE SET next_seq = pos_sale_counters.next_seq + 1 "
                                + "RETURNING next_seq")
                .setParameter("t", tenantId.toString())
                .setParameter("d", today)
                .getSingleResult();
        int seq = ((Number) result).intValue();
        return String.format("S-%s-%04d", today, seq);
    }

    private View view(PosSale sale) {
        List<PosSaleItem> items = itemRepository.findBySaleIdOrderByCreatedAtAsc(sale.getId());
        List<PosPayment> payments = paymentRepository.findBySaleIdOrderByPaidAtAsc(sale.getId());
        Customer customer = sale.getCustomerId() == null ? null
                : customerRepository.findById(sale.getCustomerId()).orElse(null);
        BigDecimal paid = sumPayments(payments);
        BigDecimal balance = sale.getTotal().subtract(paid);
        return new View(sale, items, payments, customer, paid, balance);
    }

    // ----- DTOs --------------------------------------------------------------

    public record View(PosSale sale, List<PosSaleItem> items, List<PosPayment> payments,
                       Customer customer, BigDecimal paid, BigDecimal balance) {
    }

    public record Receipt(View view, BigDecimal loyaltyEarned, BigDecimal change) {
    }

    public record PickerResult(UUID productId, String name, String sku, BigDecimal price,
                               int stock, boolean lowStock) {
    }

    // ----- domain exceptions -------------------------------------------------

    public static class NoOpenSessionException extends RuntimeException {
        public NoOpenSessionException() {
            super("Cashier has no OPEN session; open a shift first");
        }
    }

    public static class SaleNotOpenException extends RuntimeException {
        public SaleNotOpenException(String currentStatus) {
            super("Sale is not OPEN (currently " + currentStatus + ")");
        }
    }

    public static class SaleAlreadyCompletedException extends RuntimeException {
        public SaleAlreadyCompletedException() {
            super("Sale is already COMPLETED; use a refund (Phase 2) instead");
        }
    }

    public static class SaleHasNoItemsException extends RuntimeException {
        public SaleHasNoItemsException() {
            super("Cannot complete a sale with no items");
        }
    }

    public static class PaymentInsufficientException extends RuntimeException {
        private final BigDecimal outstanding;

        public PaymentInsufficientException(BigDecimal outstanding) {
            super("Insufficient payment; " + outstanding + " outstanding");
            this.outstanding = outstanding;
        }

        public BigDecimal getOutstanding() {
            return outstanding;
        }
    }

    public static class InsufficientStockException extends RuntimeException {
        private final UUID productId;
        private final int available;

        public InsufficientStockException(UUID productId, int available) {
            super("Insufficient stock for product " + productId + " (only " + available + ")");
            this.productId = productId;
            this.available = available;
        }

        public UUID getProductId() {
            return productId;
        }

        public int getAvailable() {
            return available;
        }
    }
}
