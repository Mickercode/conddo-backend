package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderPaymentRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The home dashboard (§11.1): KPI cards and the setup checklist. Read-only
 * aggregation over the tenant's orders, payments, and customers — all
 * RLS-scoped (every method binds the tenant first). KPI tones mirror the
 * frontend's chip palette (success / warning / danger / neutral).
 */
@Service
public class DashboardService {

    /** Stage names (lower-cased) that count as "done" — not pending/overdue. */
    private static final Set<String> TERMINAL_STAGES =
            Set.of("delivered", "completed", "done", "closed", "cancelled");

    private final OrderRepository orderRepository;
    private final OrderPaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public DashboardService(OrderRepository orderRepository, OrderPaymentRepository paymentRepository,
                            CustomerRepository customerRepository, ProductRepository productRepository,
                            TenantRepository tenantRepository, TenantSession tenantSession, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /** The four KPI cards, each {@code {value, delta, tone}}. */
    @Transactional(readOnly = true)
    public Summary summary() {
        tenantSession.bind();
        LocalDate today = LocalDate.now(clock);
        OffsetDateTime startToday = today.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endToday = startToday.plusDays(1);
        OffsetDateTime startYesterday = startToday.minusDays(1);

        BigDecimal revenueToday = nz(paymentRepository.sumAmountBetween(startToday, endToday));
        BigDecimal revenueYesterday = nz(paymentRepository.sumAmountBetween(startYesterday, startToday));
        Kpi revenue = new Kpi(revenueToday, pct(revenueToday, revenueYesterday) + " vs yesterday",
                revenueToday.compareTo(revenueYesterday) >= 0 ? "success" : "warning");

        long pending = orderRepository.countPending(TERMINAL_STAGES);
        long overdue = orderRepository.countOverdue(today, TERMINAL_STAGES);
        Kpi pendingOrders = new Kpi(pending,
                overdue == 0 ? "On track" : overdue + " need attention",
                overdue > 0 ? "warning" : "neutral");

        long newToday = customerRepository.countCreatedBetween(startToday, endToday);
        long newYesterday = customerRepository.countCreatedBetween(startYesterday, startToday);
        Kpi newCustomers = new Kpi(newToday, signed(newToday - newYesterday) + " vs yesterday",
                newToday >= newYesterday ? "success" : "warning");

        long lowStock = productRepository.countLowStock();
        Kpi lowStockItems = new Kpi(lowStock,
                lowStock == 0 ? "All stocked" : lowStock + " to reorder",
                lowStock > 0 ? "danger" : "neutral");

        return new Summary(revenue, pendingOrders, newCustomers, lowStockItems);
    }

    /** The setup-progress checklist ("2 of 6 steps"): derived state + dismissals. */
    @Transactional(readOnly = true)
    public Checklist setupChecklist() {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        Set<String> dismissed = new HashSet<>(
                tenant.getSetupDismissed() == null ? List.of() : tenant.getSetupDismissed());

        List<Step> steps = new ArrayList<>();
        steps.add(step("business_profile", "Add your business details",
                tenant.getName() != null && !tenant.getName().isBlank(), dismissed));
        steps.add(step("choose_vertical", "Choose your business type",
                tenant.getVerticalId() != null, dismissed));
        steps.add(step("add_customer", "Add your first customer",
                customerRepository.count() > 0, dismissed));
        steps.add(step("create_order", "Create your first order",
                orderRepository.count() > 0, dismissed));
        steps.add(step("set_up_website", "Set up your website", false, dismissed));
        steps.add(step("accept_payments", "Record your first payment",
                paymentRepository.count() > 0, dismissed));

        int completed = (int) steps.stream().filter(Step::done).count();
        return new Checklist(steps, completed, steps.size());
    }

    @Transactional
    public void dismissStep(String key) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        tenant.dismissSetupStep(key);
        tenantRepository.save(tenant);
    }

    // ----- internals ----------------------------------------------------------

    private Tenant requireTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    private Step step(String key, String label, boolean derivedDone, Set<String> dismissed) {
        return new Step(key, label, derivedDone || dismissed.contains(key));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** A signed percentage label vs a prior value, e.g. "+10%" or "-5%". */
    private static String pct(BigDecimal current, BigDecimal prior) {
        if (prior.signum() == 0) {
            return current.signum() > 0 ? "+100%" : "+0%";
        }
        int change = current.subtract(prior).multiply(BigDecimal.valueOf(100))
                .divide(prior, 0, RoundingMode.HALF_UP).intValue();
        return (change >= 0 ? "+" : "") + change + "%";
    }

    private static String signed(long n) {
        return (n >= 0 ? "+" : "") + n;
    }

    /** A KPI card: a raw {@code value} (number), a human {@code delta} label, and a {@code tone}. */
    public record Kpi(Object value, String delta, String tone) {
    }

    /** The four dashboard KPI cards. */
    public record Summary(Kpi revenueToday, Kpi pendingOrders, Kpi newCustomers, Kpi lowStockItems) {
    }

    /** One setup-checklist step. */
    public record Step(String key, String label, boolean done) {
    }

    /** The setup checklist with its completed/total tally. */
    public record Checklist(List<Step> steps, int completed, int total) {
    }
}
