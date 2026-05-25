package io.conddo.core.service;

import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.OrderPayment;
import io.conddo.core.notify.SmsSender;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderPaymentRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Payments dashboard (§11.7) — read-only aggregation over the orders and
 * order-payments the Orders module already records. There is no separate
 * invoice/ledger yet: an order's {@code amount} is the bill, recorded
 * {@link OrderPayment}s are receipts, and the outstanding balance is the
 * difference. Every method binds the tenant first so RLS scopes all reads.
 *
 * <p>Invoices, Paystack links, and webhook reconciliation are deferred to the
 * Billing module (§7); this service serves the summary/transactions/outstanding
 * views the frontend renders today and the reminder send.
 */
@Service
public class PaymentsService {

    /** Stage names (lower-cased) that no longer accrue against a customer as "open" work. */
    private static final Set<String> TERMINAL_STAGES =
            Set.of("delivered", "completed", "done", "closed", "cancelled");

    private final OrderRepository orderRepository;
    private final OrderPaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final SmsSender smsSender;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PaymentsService(OrderRepository orderRepository, OrderPaymentRepository paymentRepository,
                           CustomerRepository customerRepository, SmsSender smsSender,
                           TenantSession tenantSession, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
        this.smsSender = smsSender;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- summary ------------------------------------------------------------

    /** The four KPI cards: revenue in the period, total outstanding, paid orders, overdue amount. */
    @Transactional(readOnly = true)
    public Summary summary(String range) {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);
        Window window = window(range, today);

        BigDecimal thisMonth = nz(paymentRepository.sumAmountBetween(window.start(), window.end()));

        Map<UUID, BigDecimal> paidByOrder = paidByOrder();
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        long paidInvoices = 0;
        for (Order order : orderRepository.findAll()) {
            BigDecimal balance = balanceOf(order, paidByOrder);
            if (balance.signum() > 0) {
                outstanding = outstanding.add(balance);
                if (isOverdue(order, today)) {
                    overdue = overdue.add(balance);
                }
            } else if (order.getAmount().signum() > 0) {
                paidInvoices++;
            }
        }
        return new Summary(thisMonth, outstanding, paidInvoices, overdue);
    }

    // ----- transactions -------------------------------------------------------

    /**
     * The transactions ledger, newest first. Recorded payments are
     * {@code received} rows; orders still carrying a balance surface as
     * {@code outstanding}/{@code overdue} rows so the table mirrors the filter
     * tabs (All / Received / Outstanding / Overdue).
     */
    @Transactional(readOnly = true)
    public Page transactions(String filter, LocalDate from, LocalDate to, int page, int size) {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);
        String tab = filter == null || filter.isBlank() ? "all" : filter.trim().toLowerCase();
        Map<UUID, Order> ordersById = new HashMap<>();
        for (Order o : orderRepository.findAll()) {
            ordersById.put(o.getId(), o);
        }

        List<Txn> rows = new ArrayList<>();
        if (tab.equals("all") || tab.equals("received")) {
            for (OrderPayment p : paymentRepository.findAllByOrderByPaidAtDesc()) {
                Order order = ordersById.get(p.getOrderId());
                rows.add(new Txn(p.getPaidAt(), customerName(order), description(order),
                        p.getAmount(), p.getMethod(), "received"));
            }
        }
        if (tab.equals("all") || tab.equals("outstanding") || tab.equals("overdue")) {
            Map<UUID, BigDecimal> paidByOrder = paidByOrder();
            for (Order order : ordersById.values()) {
                BigDecimal balance = balanceOf(order, paidByOrder);
                if (balance.signum() <= 0) {
                    continue;
                }
                boolean overdue = isOverdue(order, today);
                if (tab.equals("overdue") && !overdue) {
                    continue;
                }
                rows.add(new Txn(dueOrCreated(order), customerName(order), description(order),
                        balance, null, overdue ? "overdue" : "outstanding"));
            }
        }

        rows.removeIf(t -> !withinRange(t.date(), from, to));
        rows.sort(Comparator.comparing(Txn::date).reversed());
        return paginate(rows, page, size);
    }

    // ----- outstanding by customer --------------------------------------------

    /** Open balances grouped by customer, worst (overdue, largest) first — the "send reminder" list. */
    @Transactional(readOnly = true)
    public List<OutstandingGroup> outstanding() {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);
        Map<UUID, BigDecimal> paidByOrder = paidByOrder();

        // Key by customer id when linked, else by the snapshotted name (walk-in orders).
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Order order : orderRepository.findAll()) {
            BigDecimal balance = balanceOf(order, paidByOrder);
            if (balance.signum() <= 0) {
                continue;
            }
            String key = order.getCustomerId() != null
                    ? "id:" + order.getCustomerId() : "name:" + customerName(order);
            Group g = groups.computeIfAbsent(key,
                    k -> new Group(order.getCustomerId(), customerName(order)));
            g.amount = g.amount.add(balance);
            g.orders++;
            if (isOverdue(order, today)) {
                g.overdue++;
            }
        }

        return groups.values().stream()
                .map(g -> new OutstandingGroup(g.customerId, g.name, noteFor(g), g.amount,
                        g.overdue > 0 ? "danger" : "warning"))
                .sorted(Comparator.<OutstandingGroup, Integer>comparing(o -> "danger".equals(o.tone()) ? 0 : 1)
                        .thenComparing(o -> o.amount().negate()))
                .toList();
    }

    /** Sends a payment reminder (SMS) to a customer with an outstanding balance. */
    @Transactional
    public void remindCustomer(UUID customerId, String message) {
        tenantSession.bind();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new io.conddo.core.common.NotFoundException("Customer not found"));
        if (customer.getPhone() == null || customer.getPhone().isBlank()) {
            throw new IllegalArgumentException("Customer has no phone number on file");
        }
        Map<UUID, BigDecimal> paidByOrder = paidByOrder();
        BigDecimal owed = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(o -> balanceOf(o, paidByOrder))
                .filter(b -> b.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String text = (message == null || message.isBlank())
                ? "Hi " + customer.getFullName() + ", a friendly reminder that you have an outstanding "
                        + "balance of " + owed + ". Thank you."
                : message;
        smsSender.send(customer.getPhone(), text);
    }

    // ----- internals ----------------------------------------------------------

    private Map<UUID, BigDecimal> paidByOrder() {
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (Object[] row : paymentRepository.sumByOrder()) {
            paid.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return paid;
    }

    private static BigDecimal balanceOf(Order order, Map<UUID, BigDecimal> paidByOrder) {
        return order.getAmount().subtract(paidByOrder.getOrDefault(order.getId(), BigDecimal.ZERO));
    }

    private boolean isOverdue(Order order, LocalDate today) {
        return order.getDueDate() != null && order.getDueDate().isBefore(today)
                && !TERMINAL_STAGES.contains(order.getStage().toLowerCase());
    }

    private static String customerName(Order order) {
        if (order == null) {
            return "—";
        }
        return order.getCustomerName() == null || order.getCustomerName().isBlank()
                ? "Walk-in" : order.getCustomerName();
    }

    private static String description(Order order) {
        if (order == null) {
            return "Payment";
        }
        if (order.getService() != null && !order.getService().isBlank()) {
            return order.getService();
        }
        return order.getReference() != null ? order.getReference() : "Order";
    }

    private static OffsetDateTime dueOrCreated(Order order) {
        return order.getDueDate() != null
                ? order.getDueDate().atStartOfDay().atOffset(ZoneOffset.UTC) : order.getCreatedAt();
    }

    private static String noteFor(Group g) {
        String orders = g.orders + (g.orders == 1 ? " order" : " orders");
        return g.overdue > 0 ? orders + " · " + g.overdue + " overdue" : orders;
    }

    private static boolean withinRange(OffsetDateTime at, LocalDate from, LocalDate to) {
        if (at == null) {
            return false;
        }
        LocalDate d = at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    /** Resolves a {@code range} token to a [start, end) window; defaults to the current month. */
    private static Window window(String range, LocalDate today) {
        String r = range == null ? "" : range.trim().toLowerCase();
        LocalDate start = switch (r) {
            case "week" -> today.minusDays(6);
            case "year" -> today.withDayOfYear(1);
            case "all" -> LocalDate.of(2000, 1, 1);
            default -> today.withDayOfMonth(1);
        };
        return new Window(start.atStartOfDay().atOffset(ZoneOffset.UTC),
                today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    private static Page paginate(List<Txn> rows, int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? 20 : size;
        int from = Math.min(p * s, rows.size());
        int to = Math.min(from + s, rows.size());
        return new Page(rows.subList(from, to), p, s, rows.size());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ----- accumulator + records ----------------------------------------------

    private static final class Group {
        private final UUID customerId;
        private final String name;
        private BigDecimal amount = BigDecimal.ZERO;
        private int orders;
        private int overdue;

        private Group(UUID customerId, String name) {
            this.customerId = customerId;
            this.name = name;
        }
    }

    /** Payments KPI cards (§11.7). Amounts are naira totals; {@code paidInvoices} is a count. */
    public record Summary(BigDecimal thisMonth, BigDecimal outstanding, long paidInvoices, BigDecimal overdue) {
    }

    /** One transactions-table row. {@code status} ∈ received | outstanding | overdue. */
    public record Txn(OffsetDateTime date, String customer, String description,
                      BigDecimal amount, String method, String status) {
    }

    /** Outstanding balance grouped by customer; {@code customerId} is the reminder target (may be null). */
    public record OutstandingGroup(UUID customerId, String name, String note, BigDecimal amount, String tone) {
    }

    /** A page of transactions plus its pagination counters. */
    public record Page(List<Txn> content, int page, int size, long total) {
    }

    private record Window(OffsetDateTime start, OffsetDateTime end) {
    }
}
