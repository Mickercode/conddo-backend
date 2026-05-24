package io.conddo.core.service;

import io.conddo.core.audit.AuditContext;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.OrderActivity;
import io.conddo.core.domain.OrderItem;
import io.conddo.core.domain.OrderPayment;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderActivityRepository;
import io.conddo.core.repository.OrderItemRepository;
import io.conddo.core.repository.OrderPaymentRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.repository.UserRepository;
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
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped order pipeline (§11.4): the Kanban board, list, detail, stage
 * transitions, line items, payments, and the per-order activity log. Every
 * method binds the tenant first so RLS scopes all reads/writes — no manual
 * {@code WHERE tenant_id}. Stages come from {@link OrderStageService} (the
 * tenant's overrides or the vertical defaults).
 */
@Service
public class OrderService {

    /** Stage names (lower-cased) that count as "done" — never flagged OVERDUE. */
    private static final Set<String> TERMINAL_STAGES =
            Set.of("delivered", "completed", "done", "closed", "cancelled");

    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderPaymentRepository paymentRepository;
    private final OrderActivityRepository activityRepository;
    private final OrderStageService stageService;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final SmsSender smsSender;
    private final TenantSession tenantSession;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderItemRepository itemRepository,
                        OrderPaymentRepository paymentRepository, OrderActivityRepository activityRepository,
                        OrderStageService stageService, CustomerRepository customerRepository,
                        UserRepository userRepository, SmsSender smsSender,
                        TenantSession tenantSession, Clock clock) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.activityRepository = activityRepository;
        this.stageService = stageService;
        this.customerRepository = customerRepository;
        this.userRepository = userRepository;
        this.smsSender = smsSender;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- board / list -------------------------------------------------------

    /** Kanban board: one column per pipeline stage, in order, with its cards. */
    @Transactional(readOnly = true)
    public Board board(String search, String filter) {
        tenantSession.bind();
        List<String> stageNames = stageService.effectiveStageNames();
        List<Order> orders = orderRepository.findAll(filteredBy(search, filter, null));

        // Preserve pipeline order, but also surface any straggler stages present in
        // the data yet missing from the pipeline (e.g. a renamed/removed stage).
        List<String> columns = new ArrayList<>(stageNames);
        for (Order o : orders) {
            if (!columns.contains(o.getStage())) {
                columns.add(o.getStage());
            }
        }

        List<Column> result = new ArrayList<>();
        for (String name : columns) {
            List<OrderView> cards = new ArrayList<>();
            for (Order o : orders) {
                if (name.equals(o.getStage())) {
                    cards.add(view(o));
                }
            }
            result.add(new Column(name, cards));
        }
        return new Board(result);
    }

    /** Flat, paginated list (list view + the dashboard's recent-orders widget). */
    @Transactional(readOnly = true)
    public Page<OrderView> list(String search, String filter, String stage, Pageable pageable) {
        tenantSession.bind();
        return orderRepository.findAll(filteredBy(search, filter, stage), pageable).map(this::view);
    }

    // ----- single order -------------------------------------------------------

    @Transactional
    public Order create(UUID customerId, String customerName, String service, String stage,
                        BigDecimal amount, LocalDate dueDate, List<NewItem> items,
                        Map<String, Object> measurements, String notes) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();

        String resolvedName = customerName;
        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
            resolvedName = customer.getFullName();
        }
        String resolvedStage = (stage == null || stage.isBlank()) ? stageService.firstStage() : stage;

        Order order = new Order(tenantId, customerId, resolvedName, service, resolvedStage);
        order.setDueDate(dueDate);
        order.setNotes(notes);
        order.setMeasurements(measurements);
        order.setAmount(amount);
        order = orderRepository.save(order);

        if (items != null && !items.isEmpty()) {
            for (NewItem i : items) {
                itemRepository.save(new OrderItem(tenantId, order.getId(),
                        i.description(), i.quantity() <= 0 ? 1 : i.quantity(), i.unitPrice()));
            }
            recomputeAmount(order);
        }

        log(order, "CREATED", "Order created", resolvedStage);
        return order;
    }

    @Transactional(readOnly = true)
    public Order get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    /** Everything the order detail page needs, loaded in one transaction. */
    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        tenantSession.bind();
        Order order = require(id);
        Customer customer = order.getCustomerId() == null ? null
                : customerRepository.findById(order.getCustomerId()).orElse(null);
        return new Detail(order, flagOf(order), stageService.effectiveStageNames(), billingFor(order),
                itemRepository.findByOrderIdOrderByCreatedAt(id),
                paymentRepository.findByOrderIdOrderByPaidAtDesc(id),
                activityRepository.findByOrderIdOrderByCreatedAtDesc(id), customer);
    }

    @Transactional
    public Order update(UUID id, String service, LocalDate dueDate, String flag,
                        BigDecimal amount, String notes, boolean amountGiven) {
        tenantSession.bind();
        Order order = require(id);
        if (service != null) {
            order.setService(service);
        }
        if (dueDate != null) {
            order.setDueDate(dueDate);
        }
        if (flag != null) {
            order.setFlag(flag.isBlank() ? null : flag);
        }
        if (notes != null) {
            order.setNotes(notes);
        }
        // amount is normally derived from items; only honour an explicit value.
        if (amountGiven && itemRepository.findByOrderIdOrderByCreatedAt(id).isEmpty()) {
            order.setAmount(amount);
        }
        return orderRepository.save(order);
    }

    @Transactional
    public Order transition(UUID id, String toStage) {
        tenantSession.bind();
        if (toStage == null || toStage.isBlank()) {
            throw new IllegalArgumentException("Target stage is required");
        }
        if (!stageService.effectiveStageNames().contains(toStage)) {
            throw new NotFoundException("Unknown stage: " + toStage);
        }
        Order order = require(id);
        String from = order.getStage();
        order.setStage(toStage);
        order = orderRepository.save(order);
        log(order, "STAGE_CHANGE", "Moved to \"" + toStage + "\"", "from \"" + from + "\"");
        return order;
    }

    @Transactional
    public Order setMeasurements(UUID id, Map<String, Object> measurements) {
        tenantSession.bind();
        Order order = require(id);
        order.setMeasurements(measurements);
        return orderRepository.save(order);
    }

    // ----- items --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OrderItem> items(UUID orderId) {
        tenantSession.bind();
        require(orderId);
        return itemRepository.findByOrderIdOrderByCreatedAt(orderId);
    }

    @Transactional
    public OrderItem addItem(UUID orderId, String description, Integer quantity, BigDecimal unitPrice) {
        tenantSession.bind();
        Order order = require(orderId);
        OrderItem item = itemRepository.save(new OrderItem(TenantContext.require(), orderId,
                description, quantity == null || quantity <= 0 ? 1 : quantity, unitPrice));
        recomputeAmount(order);
        return item;
    }

    @Transactional
    public OrderItem updateItem(UUID orderId, UUID itemId, String description, Integer quantity, BigDecimal unitPrice) {
        tenantSession.bind();
        Order order = require(orderId);
        OrderItem item = requireItem(orderId, itemId);
        item.setDescription(description);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item = itemRepository.save(item);
        recomputeAmount(order);
        return item;
    }

    @Transactional
    public void deleteItem(UUID orderId, UUID itemId) {
        tenantSession.bind();
        Order order = require(orderId);
        itemRepository.delete(requireItem(orderId, itemId));
        recomputeAmount(order);
    }

    // ----- payments -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OrderPayment> payments(UUID orderId) {
        tenantSession.bind();
        require(orderId);
        return paymentRepository.findByOrderIdOrderByPaidAtDesc(orderId);
    }

    @Transactional
    public OrderPayment addPayment(UUID orderId, BigDecimal amount, String method, String note) {
        tenantSession.bind();
        Order order = require(orderId);
        OrderPayment payment = paymentRepository.save(
                new OrderPayment(TenantContext.require(), orderId, amount, method, note));
        log(order, "PAYMENT", "Payment recorded",
                amount + (method == null ? "" : " via " + method));
        return payment;
    }

    /** Billing summary for an order: total, paid (sum of payments), and balance. */
    @Transactional(readOnly = true)
    public Billing billing(UUID orderId) {
        tenantSession.bind();
        Order order = require(orderId);
        return billingFor(order);
    }

    // ----- activity / reminders ----------------------------------------------

    @Transactional(readOnly = true)
    public List<OrderActivity> activity(UUID orderId) {
        tenantSession.bind();
        require(orderId);
        return activityRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    /** Sends a payment/pickup reminder to the order's customer (SMS) and logs it. */
    @Transactional
    public void remind(UUID orderId, String message) {
        tenantSession.bind();
        Order order = require(orderId);
        String text = (message == null || message.isBlank())
                ? "Reminder about your order " + order.getReference() : message;
        String phone = order.getCustomerId() == null ? null
                : customerRepository.findById(order.getCustomerId()).map(Customer::getPhone).orElse(null);
        if (phone != null && !phone.isBlank()) {
            smsSender.send(phone, text);
        }
        log(order, "MESSAGE", "Reminder sent", text);
    }

    // ----- view helpers (flag is business logic, not stored) ------------------

    /** Effective flag: an explicit URGENT/flag wins; otherwise derive OVERDUE. */
    public String flagOf(Order order) {
        if (order.getFlag() != null && !order.getFlag().isBlank()) {
            return order.getFlag();
        }
        LocalDate today = LocalDate.now(clock);
        boolean overdue = order.getDueDate() != null
                && order.getDueDate().isBefore(today)
                && !TERMINAL_STAGES.contains(order.getStage().toLowerCase());
        return overdue ? "OVERDUE" : null;
    }

    public Billing billingFor(Order order) {
        BigDecimal paid = paymentRepository.findByOrderIdOrderByPaidAtDesc(order.getId()).stream()
                .map(OrderPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Billing(order.getAmount(), paid, order.getAmount().subtract(paid));
    }

    private OrderView view(Order order) {
        return new OrderView(order, flagOf(order));
    }

    // ----- internals ----------------------------------------------------------

    private void recomputeAmount(Order order) {
        List<OrderItem> items = itemRepository.findByOrderIdOrderByCreatedAt(order.getId());
        if (!items.isEmpty()) {
            BigDecimal total = items.stream().map(OrderItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            order.setAmount(total);
            orderRepository.save(order);
        }
    }

    private void log(Order order, String type, String title, String detail) {
        activityRepository.save(new OrderActivity(
                order.getTenantId(), order.getId(), type, title, detail, currentActorName()));
    }

    /** The current user's display name for the activity log, or null if unknown. */
    private String currentActorName() {
        return AuditContext.getActor()
                .flatMap(userRepository::findById)
                .map(io.conddo.core.domain.User::getFullName)
                .orElse(null);
    }

    private Order require(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    private OrderItem requireItem(UUID orderId, UUID itemId) {
        OrderItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Item not found"));
        if (!orderId.equals(item.getOrderId())) {
            throw new NotFoundException("Item not found");
        }
        return item;
    }

    private Specification<Order> filteredBy(String search, String filter, String stage) {
        LocalDate today = LocalDate.now(clock);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("reference"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("customerName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("service"), "")), like)));
            }
            if (stage != null && !stage.isBlank()) {
                predicates.add(cb.equal(root.get("stage"), stage));
            }
            if (filter != null) {
                switch (filter) {
                    case "today" -> predicates.add(cb.equal(root.get("dueDate"), today));
                    case "week" -> predicates.add(cb.between(root.get("dueDate"), today, today.plusDays(7)));
                    case "overdue" -> predicates.add(cb.and(
                            cb.lessThan(root.get("dueDate"), today),
                            cb.not(cb.lower(root.get("stage")).in(TERMINAL_STAGES))));
                    default -> {
                        // "all" or unknown → no extra predicate
                    }
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** A line item to create with an order. */
    public record NewItem(String description, int quantity, BigDecimal unitPrice) {
    }

    /** An order plus its computed flag, for board cards and list rows. */
    public record OrderView(Order order, String flag) {
    }

    /** Kanban board: ordered stage columns. */
    public record Board(List<Column> stages) {
    }

    /** One Kanban column: a stage name and its cards. */
    public record Column(String name, List<OrderView> orders) {
    }

    /** Order billing: total, amount paid so far, and the outstanding balance. */
    public record Billing(BigDecimal total, BigDecimal deposit, BigDecimal balance) {
    }

    /** Aggregate for the order detail page. {@code customer} may be null. */
    public record Detail(Order order, String flag, List<String> stages, Billing billing,
                         List<OrderItem> items, List<OrderPayment> payments,
                         List<OrderActivity> activity, Customer customer) {
    }
}
