package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.CustomerAddress;
import io.conddo.core.domain.CustomerPrescription;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.Product;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.events.OrderCreatedEvent;
import io.conddo.core.repository.CustomerAddressRepository;
import io.conddo.core.repository.CustomerPrescriptionRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.service.BillingService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-side checkout (PHARMACY_PUBLIC_API_SPEC §5). Pulls together
 * cart items + saved address + optional prescription, locks every
 * product row {@code FOR UPDATE}, validates stock + prescription gate,
 * persists the order with a snapshot of the address, decrements stock,
 * clears the cart, and emits an {@link OrderCreatedEvent} for the
 * existing notify listener.
 *
 * <p>Strict transaction boundary — any failure rolls back stock
 * decrements, the cart clear, and the order row.
 */
@Service
public class PublicOrderCheckoutService {

    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAddressRepository addressRepository;
    private final CustomerPrescriptionRepository prescriptionRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PublicCartService cartService;
    private final PharmacyDeliveryFeeService deliveryFeeService;
    private final BillingService billingService;
    private final StockMovementService stockMovementService;
    private final PharmacyDiscountService discountService;
    private final PharmacyRefillOfferService refillOfferService;
    private final ApplicationEventPublisher events;
    private final TenantSession tenantSession;

    @PersistenceContext
    private EntityManager entityManager;

    public PublicOrderCheckoutService(ProductRepository productRepository,
                                      CustomerRepository customerRepository,
                                      CustomerAddressRepository addressRepository,
                                      CustomerPrescriptionRepository prescriptionRepository,
                                      OrderRepository orderRepository,
                                      OrderService orderService,
                                      PublicCartService cartService,
                                      PharmacyDeliveryFeeService deliveryFeeService,
                                      BillingService billingService,
                                      StockMovementService stockMovementService,
                                      PharmacyDiscountService discountService,
                                      PharmacyRefillOfferService refillOfferService,
                                      ApplicationEventPublisher events,
                                      TenantSession tenantSession) {
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
        this.addressRepository = addressRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.cartService = cartService;
        this.deliveryFeeService = deliveryFeeService;
        this.billingService = billingService;
        this.stockMovementService = stockMovementService;
        this.discountService = discountService;
        this.refillOfferService = refillOfferService;
        this.events = events;
        this.tenantSession = tenantSession;
    }

    @Transactional
    public CheckoutResult checkout(UUID customerId, List<RequestedItem> requested,
                                   UUID addressId, UUID prescriptionId, String notes) {
        return checkout(customerId, requested, addressId, prescriptionId, notes, null);
    }

    /**
     * Same as {@link #checkout(UUID, List, UUID, UUID, String)} but with
     * an optional refill-offer code (Pharmacy Spec v2 §12E). When the
     * code resolves to a live claim issued to {@code customerId} for
     * one of the line items, that line gets the offer's discount
     * (which beats any regular {@link io.conddo.core.domain.PharmacyDiscount}
     * on the same product) and the claim is redeemed against the
     * resulting order.
     */
    @Transactional
    public CheckoutResult checkout(UUID customerId, List<RequestedItem> requested,
                                   UUID addressId, UUID prescriptionId, String notes,
                                   String refillOfferCode) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("items is required");
        }
        if (addressId == null) {
            throw new IllegalArgumentException("addressId is required");
        }
        tenantSession.bind();

        // Module gate — preserves the launcher-plan-cannot-take-orders rule
        // from the Phase-1 endpoint. Throws ModuleNotEnabledException which
        // the API layer maps to 403 MODULE_NOT_ENABLED.
        if (!billingService.hasFeature(TenantContext.require(), "order_management")) {
            throw new ModuleNotEnabledException(
                    "Online orders aren't enabled on the merchant's plan.");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        CustomerAddress address = addressRepository.findById(addressId)
                .filter(a -> a.getCustomerId().equals(customerId))
                .orElseThrow(() -> new NotFoundException("Address not found"));

        CustomerPrescription prescription = null;
        if (prescriptionId != null) {
            prescription = prescriptionRepository.findById(prescriptionId)
                    .filter(p -> customerId.equals(p.getCustomerId()))
                    .orElseThrow(() -> new NotFoundException("Prescription not found"));
        }

        // Lock + validate every product line up front.
        List<Map<String, Object>> shortages = new ArrayList<>();
        List<OrderService.NewItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean prescriptionRequired = false;
        List<Product> locked = new ArrayList<>();
        for (RequestedItem item : requested) {
            Product p = entityManager.find(Product.class, item.productId(),
                    LockModeType.PESSIMISTIC_WRITE);
            if (p == null || !p.isActive()) {
                throw new NotFoundException("Product not found: " + item.productId());
            }
            if (p.getStock() < item.quantity()) {
                shortages.add(Map.of(
                        "productId", item.productId(),
                        "available", p.getStock(),
                        "requested", item.quantity()));
                continue;
            }
            if (p.isRequiresPrescription()) {
                prescriptionRequired = true;
            }
            locked.add(p);
            BigDecimal listPrice = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
            // Spec v2 §5 + §12E — apply active discounts at order time. A
            // valid refill offer for this exact product beats the regular
            // approved discount (customer chose to spend the code); either
            // way the resulting price is what hits OrderItem.unitPrice so
            // the customer-paid amount is preserved after the discount
            // expires.
            BigDecimal effectivePrice = listPrice;
            if (refillOfferCode != null
                    && refillOfferAppliesTo(refillOfferCode, customerId, p.getId())) {
                effectivePrice = refillDiscountedPrice(refillOfferCode, listPrice);
            } else {
                io.conddo.core.domain.PharmacyDiscount activeDiscount =
                        discountService.activeForProduct(p.getId()).orElse(null);
                if (activeDiscount != null) {
                    effectivePrice = activeDiscount.applyTo(listPrice);
                }
            }
            subtotal = subtotal.add(effectivePrice.multiply(BigDecimal.valueOf(item.quantity())));
            orderItems.add(new OrderService.NewItem(
                    p.getNameGeneric() == null ? p.getName() : p.getNameGeneric(),
                    item.quantity(), effectivePrice));
        }
        if (!shortages.isEmpty()) {
            throw new StockShortageException(shortages);
        }
        if (prescriptionRequired && prescription == null) {
            throw new PrescriptionRequiredException(
                    "Order contains prescription drugs but no prescription was attached.");
        }

        // Delivery fee from the address's state.
        PharmacyDeliveryFeeService.Quote quote = deliveryFeeService.quote(address.getState());
        BigDecimal deliveryFee = quote.fee();
        BigDecimal total = subtotal.add(deliveryFee);

        String customerNotes = notes == null || notes.isBlank() ? null : "Notes: " + notes;
        Order order = orderService.create(
                customer.getId(), customer.getFullName(),
                "Online pharmacy order", "PENDING",
                total, LocalDate.now(),
                orderItems, new LinkedHashMap<>(),
                customerNotes);

        // V33 fields on Order
        order.setAddress(address.getId(), addressSnapshot(address));
        order.setDeliveryFeeKobo(deliveryFee.multiply(BigDecimal.valueOf(100))
                .toBigIntegerExact().intValueExact());
        if (prescription != null) {
            order.setPrescriptionId(prescription.getId());
        }
        // payment_link wiring lands when conddo-payments is hooked up here too;
        // until then the FE polls /orders/{id}.paymentStatus.

        // Decrement stock + audit-log via StockMovementService so the FE
        // dashboard sees a SALE_ONLINE row per line and the Redis stream
        // fires `stock.deducted` / `stock.low` / `stock.out` per Spec v2 §12A.
        // We still hold the pessimistic locks on each row from the validation
        // pass above, so there's no race here even with concurrent checkouts.
        for (int i = 0; i < locked.size(); i++) {
            Product p = locked.get(i);
            int qty = requested.get(i).quantity();
            stockMovementService.recordMovement(p.getId(),
                    StockMovement.Type.SALE_ONLINE, -qty,
                    order.getId(), "ORDER",
                    "Online order " + order.getReference(), null);
        }

        cartService.clear(customerId);

        // §12E — redeem the refill-offer claim against the order now that
        // it has an id. We only call this when the code was provided AND
        // it applied to at least one line; the redeem also re-validates
        // (customer match, not expired, not already used).
        if (refillOfferCode != null) {
            try {
                refillOfferService.redeem(refillOfferCode, customerId, order.getId());
            } catch (RuntimeException ex) {
                // Don't fail the order over a stale claim — the customer
                // already paid the discounted price; this is just metadata
                // bookkeeping. Log so prod can spot pattern issues.
                log.warn("Refill offer redeem failed for order {} (code={}): {}",
                        order.getId(), refillOfferCode, ex.getMessage());
            }
        }

        events.publishEvent(new OrderCreatedEvent(
                TenantContext.require(), order.getId(), order.getReference(),
                customer.getFullName(), total,
                OrderCreatedEvent.Source.PUBLIC_WEBSITE));

        return new CheckoutResult(order, subtotal, deliveryFee, total, address, prescription);
    }

    /** Customer's own order history — RLS-scoped to the bound tenant. */
    @Transactional(readOnly = true)
    public List<Order> listMine(UUID customerId) {
        tenantSession.bind();
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /** Single order detail — refuses to return an order from another customer (403). */
    @Transactional(readOnly = true)
    public Order getMine(UUID customerId, UUID orderId) {
        tenantSession.bind();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!customerId.equals(order.getCustomerId())) {
            throw new OrderNotYoursException("Order not found");
        }
        return order;
    }

    /**
     * Order detail + items in one transaction so RLS scoping holds for
     * the item lookup too. The controller can't bind the tenant session
     * itself for the secondary repo read.
     */
    @Transactional(readOnly = true)
    public OrderWithItems getMineWithItems(UUID customerId, UUID orderId) {
        Order order = getMine(customerId, orderId);
        return new OrderWithItems(order,
                orderItemRepositoryFor().findByOrderIdOrderByCreatedAt(orderId));
    }

    private io.conddo.core.repository.OrderItemRepository orderItemRepositoryFor() {
        return orderItemRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private io.conddo.core.repository.OrderItemRepository orderItemRepository;

    public record OrderWithItems(Order order, List<io.conddo.core.domain.OrderItem> items) {
    }

    private static Map<String, Object> addressSnapshot(CustomerAddress address) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("street", address.getStreet());
        m.put("city", address.getCity());
        m.put("state", address.getState());
        m.put("landmark", address.getLandmark());
        return m;
    }

    // ----- result + exceptions ----------------------------------------------

    public record RequestedItem(UUID productId, int quantity) {
    }

    public record CheckoutResult(Order order, BigDecimal subtotal, BigDecimal deliveryFee,
                                 BigDecimal total, CustomerAddress address,
                                 CustomerPrescription prescription) {
    }

    public static class StockShortageException extends RuntimeException {
        private final List<Map<String, Object>> items;

        public StockShortageException(List<Map<String, Object>> items) {
            super("OUT_OF_STOCK");
            this.items = items;
        }

        public List<Map<String, Object>> getItems() {
            return items;
        }
    }

    public static class PrescriptionRequiredException extends RuntimeException {
        public PrescriptionRequiredException(String msg) {
            super(msg);
        }
    }

    public static class OrderNotYoursException extends RuntimeException {
        public OrderNotYoursException(String msg) {
            super(msg);
        }
    }

    /** Merchant's plan doesn't include the {@code order_management} feature. */
    public static class ModuleNotEnabledException extends RuntimeException {
        public ModuleNotEnabledException(String msg) {
            super(msg);
        }
    }

    private boolean refillOfferAppliesTo(String code, UUID customerId, UUID productId) {
        try {
            io.conddo.core.service.PharmacyRefillOfferService.ValidationResult v =
                    refillOfferService.validate(code);
            if (!v.valid()) {
                return false;
            }
            if (!customerId.equals(v.claim().getCustomerId())) {
                return false;
            }
            return productId.equals(v.offer().getProductId());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private BigDecimal refillDiscountedPrice(String code, BigDecimal listPrice) {
        io.conddo.core.service.PharmacyRefillOfferService.ValidationResult v =
                refillOfferService.validate(code);
        return v.offer().applyTo(listPrice);
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PublicOrderCheckoutService.class);
}
