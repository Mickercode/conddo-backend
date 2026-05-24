package io.conddo.core.service;

import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Order;
import io.conddo.core.domain.OrderPayment;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.OrderItemRepository;
import io.conddo.core.repository.OrderPaymentRepository;
import io.conddo.core.repository.OrderRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Business analytics (§11.9): read-only aggregation over orders, payments,
 * customers, and inventory — no new tables. Time-series are bucketed in-memory
 * (DB-agnostic; fine for current volumes — switch to {@code date_trunc} native
 * aggregation if datasets grow). Every method binds the tenant first (RLS), so
 * all figures are this tenant's only. Website traffic is a placeholder until §11.2.
 */
@Service
public class AnalyticsService {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final Pageable TOP_5 = PageRequest.of(0, 5);

    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final OrderPaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public AnalyticsService(OrderRepository orderRepository, OrderItemRepository itemRepository,
                            OrderPaymentRepository paymentRepository, CustomerRepository customerRepository,
                            TenantSession tenantSession, Clock clock) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.customerRepository = customerRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(String range) {
        tenantSession.bind();
        OffsetDateTime start = startOf(range);
        OffsetDateTime end = now();
        BigDecimal revenue = nz(paymentRepository.sumAmountBetween(start, end));
        long orders = orderRepository.findByCreatedAtBetween(start, end).size();
        long newCustomers = customerRepository.countCreatedBetween(start, end);
        BigDecimal aov = orders == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
        return new Overview(revenue, orders, newCustomers, aov);
    }

    @Transactional(readOnly = true)
    public List<SeriesPoint> revenueSeries(String range, String granularity) {
        tenantSession.bind();
        Map<LocalDate, BigDecimal> buckets = new TreeMap<>();
        for (OrderPayment p : paymentRepository.findByPaidAtBetween(startOf(range), now())) {
            buckets.merge(bucket(p.getPaidAt(), granularity), p.getAmount(), BigDecimal::add);
        }
        return toSeries(buckets);
    }

    @Transactional(readOnly = true)
    public List<SeriesPoint> ordersSeries(String range, String granularity) {
        tenantSession.bind();
        Map<LocalDate, BigDecimal> buckets = new TreeMap<>();
        for (Order o : orderRepository.findByCreatedAtBetween(startOf(range), now())) {
            buckets.merge(bucket(o.getCreatedAt(), granularity), BigDecimal.ONE, BigDecimal::add);
        }
        return toSeries(buckets);
    }

    @Transactional(readOnly = true)
    public CustomersAnalytics customers(String range, String granularity) {
        tenantSession.bind();
        OffsetDateTime start = startOf(range);
        OffsetDateTime end = now();
        long total = customerRepository.count();
        long returning = orderRepository.returningCustomerIds().size();
        Map<LocalDate, BigDecimal> buckets = new TreeMap<>();
        long newCustomers = 0;
        for (Customer c : customerRepository.findByCreatedAtBetween(start, end)) {
            buckets.merge(bucket(c.getCreatedAt(), granularity), BigDecimal.ONE, BigDecimal::add);
            newCustomers++;
        }
        return new CustomersAnalytics(newCustomers, returning, total, toSeries(buckets));
    }

    @Transactional(readOnly = true)
    public List<TopEntry> top(String metric) {
        tenantSession.bind();
        List<Object[]> rows = switch (metric == null ? "services" : metric.toLowerCase()) {
            case "products" -> itemRepository.topItemsByQuantity(TOP_5);
            case "customers" -> orderRepository.topCustomers(TOP_5);
            default -> orderRepository.topServices(TOP_5);
        };
        List<TopEntry> entries = new ArrayList<>();
        for (Object[] row : rows) {
            entries.add(new TopEntry((String) row[0], ((Number) row[1]).longValue()));
        }
        return entries;
    }

    /** Website traffic/conversion — a placeholder until the Website module (§11.2) lands. */
    @Transactional(readOnly = true)
    public Traffic traffic(String range) {
        return new Traffic(0, 0, 0.0);
    }

    // ----- internals ----------------------------------------------------------

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private OffsetDateTime startOf(String range) {
        return now().minusDays(parseDays(range));
    }

    /** Parses ranges like "7d", "4w", "6m" (days/weeks/months) → days; default 30. */
    private static long parseDays(String range) {
        if (range == null || range.isBlank()) {
            return DEFAULT_RANGE_DAYS;
        }
        String r = range.trim().toLowerCase();
        char unit = r.charAt(r.length() - 1);
        try {
            if (unit == 'd' || unit == 'w' || unit == 'm') {
                long n = Long.parseLong(r.substring(0, r.length() - 1));
                return switch (unit) {
                    case 'w' -> n * 7;
                    case 'm' -> n * 30;
                    default -> n;
                };
            }
            return Long.parseLong(r);
        } catch (NumberFormatException ex) {
            return DEFAULT_RANGE_DAYS;
        }
    }

    private static LocalDate bucket(OffsetDateTime when, String granularity) {
        LocalDate day = when.toLocalDate();
        return switch (granularity == null ? "day" : granularity.toLowerCase()) {
            case "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> day.withDayOfMonth(1);
            default -> day;
        };
    }

    private static List<SeriesPoint> toSeries(Map<LocalDate, BigDecimal> buckets) {
        List<SeriesPoint> points = new ArrayList<>();
        buckets.forEach((date, value) -> points.add(new SeriesPoint(date, value)));
        return points;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Headline metrics over a range. */
    public record Overview(BigDecimal revenue, long orders, long newCustomers, BigDecimal avgOrderValue) {
    }

    /** A point in a time-series (bucket start date + value). */
    public record SeriesPoint(LocalDate date, BigDecimal value) {
    }

    /** Customer analytics: new vs returning, total, and a new-customer series. */
    public record CustomersAnalytics(long newCustomers, long returningCustomers, long total, List<SeriesPoint> series) {
    }

    /** A leaderboard entry (e.g. a service or customer) with its count. */
    public record TopEntry(String label, long value) {
    }

    /** Website traffic metrics (placeholder until §11.2). */
    public record Traffic(long visits, long enquiries, double conversionRate) {
    }
}
