package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped bookings (§11.5): the calendar, availability settings, the
 * shareable self-book link, and weekly performance. Every method binds the
 * tenant first so RLS scopes all reads/writes. Scheduling config lives on the
 * tenant row (so the PUBLIC self-book endpoint can resolve it without a tenant
 * context); appointments are RLS-scoped business data.
 */
@Service
public class BookingService {

    private static final String CANCELLED = "cancelled";

    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, CustomerRepository customerRepository,
                          TenantRepository tenantRepository, TenantSession tenantSession, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.customerRepository = customerRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /** Events in a date range (defaults to the current week when both are null). */
    @Transactional(readOnly = true)
    public List<Booking> list(LocalDate from, LocalDate to) {
        tenantSession.bind();
        OffsetDateTime start;
        OffsetDateTime end;
        if (from == null && to == null) {
            OffsetDateTime[] week = currentWeek();
            start = week[0];
            end = week[1];
        } else {
            LocalDate f = from != null ? from : LocalDate.now(clock);
            LocalDate t = to != null ? to : f.plusDays(6);
            start = f.atStartOfDay().atOffset(ZoneOffset.UTC);
            end = t.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        }
        return bookingRepository.findByStartsAtBetweenOrderByStartsAt(start, end);
    }

    @Transactional
    public Booking create(UUID customerId, String customerName, String service,
                          OffsetDateTime startsAt, OffsetDateTime endsAt, String mode,
                          BigDecimal amount, String notes, String status) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        String resolvedName = customerName;
        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
            resolvedName = customer.getFullName();
        }
        OffsetDateTime end = endsAt != null ? endsAt
                : startsAt.plusMinutes(tenant.getSlotDurationMinutes());
        Booking booking = new Booking(TenantContext.require(), customerId, resolvedName, service,
                startsAt, end, mode, status);
        booking.setAmount(amount);
        booking.setNotes(notes);
        return bookingRepository.save(booking);
    }

    @Transactional(readOnly = true)
    public Booking get(UUID id) {
        tenantSession.bind();
        return require(id);
    }

    @Transactional
    public Booking update(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, String service,
                          String mode, String status, BigDecimal amount, String notes) {
        tenantSession.bind();
        Booking booking = require(id);
        booking.reschedule(startsAt, endsAt);
        booking.setService(service);
        booking.setMode(mode);
        booking.setStatus(status);
        if (amount != null) {
            booking.setAmount(amount);
        }
        if (notes != null) {
            booking.setNotes(notes);
        }
        return bookingRepository.save(booking);
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        bookingRepository.delete(require(id));
    }

    /** Upcoming appointments over the next 7 days (excludes cancelled). */
    @Transactional(readOnly = true)
    public List<Booking> upcoming() {
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return bookingRepository.findByStartsAtBetweenAndStatusNotOrderByStartsAt(
                now, now.plusDays(7), CANCELLED);
    }

    // ----- availability / link / performance ----------------------------------

    @Transactional(readOnly = true)
    public Availability availability() {
        tenantSession.bind();
        return availabilityOf(requireTenant());
    }

    @Transactional
    public Availability updateAvailability(Map<String, Object> workingHours,
                                           Integer slotDurationMinutes, Integer bufferMinutes) {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        if (workingHours != null) {
            tenant.setWorkingHours(workingHours);
        }
        if (slotDurationMinutes != null) {
            tenant.setSlotDurationMinutes(slotDurationMinutes);
        }
        if (bufferMinutes != null) {
            tenant.setBufferMinutes(bufferMinutes);
        }
        return availabilityOf(tenantRepository.save(tenant));
    }

    @Transactional(readOnly = true)
    public Link link() {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        return new Link(tenant.effectiveBookingSlug(), tenant.isBookingLinkEnabled());
    }

    /** Regenerates the self-book slug (derived from the tenant slug + a random suffix). */
    @Transactional
    public Link regenerateLink() {
        tenantSession.bind();
        Tenant tenant = requireTenant();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        tenant.setBookingLinkSlug(tenant.getSlug() + "-" + suffix);
        tenant.setBookingLinkEnabled(true);
        tenantRepository.save(tenant);
        return new Link(tenant.effectiveBookingSlug(), tenant.isBookingLinkEnabled());
    }

    @Transactional(readOnly = true)
    public Performance performance() {
        tenantSession.bind();
        OffsetDateTime[] week = currentWeek();
        long count = bookingRepository.countByStartsAtBetweenAndStatusNot(week[0], week[1], CANCELLED);
        BigDecimal revenue = bookingRepository.sumAmountBetween(week[0], week[1], CANCELLED);
        return new Performance(count, revenue == null ? BigDecimal.ZERO : revenue);
    }

    // ----- internals ----------------------------------------------------------

    private Availability availabilityOf(Tenant tenant) {
        Map<String, Object> hours = tenant.getWorkingHours() != null
                ? tenant.getWorkingHours() : defaultWorkingHours();
        return new Availability(hours, tenant.getSlotDurationMinutes(), tenant.getBufferMinutes());
    }

    /** Monday 00:00 of the current week to the following Monday 00:00 (UTC). */
    private OffsetDateTime[] currentWeek() {
        LocalDate monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime start = monday.atStartOfDay().atOffset(ZoneOffset.UTC);
        return new OffsetDateTime[]{start, start.plusDays(7)};
    }

    /** Sensible defaults when a tenant has not configured hours: Mon–Fri 09–17. */
    static Map<String, Object> defaultWorkingHours() {
        Map<String, Object> hours = new LinkedHashMap<>();
        for (String day : List.of("mon", "tue", "wed", "thu", "fri")) {
            hours.put(day, Map.of("open", true, "start", "09:00", "end", "17:00"));
        }
        for (String day : List.of("sat", "sun")) {
            hours.put(day, Map.of("open", false, "start", "09:00", "end", "17:00"));
        }
        return hours;
    }

    private Tenant requireTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    private Booking require(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
    }

    /** Availability config: working hours by weekday, slot length, and buffer. */
    public record Availability(Map<String, Object> workingHours, int slotDurationMinutes, int bufferMinutes) {
    }

    /** The shareable self-book link's slug and on/off state. */
    public record Link(String slug, boolean enabled) {
    }

    /** Weekly performance: confirmed bookings this week and projected revenue. */
    public record Performance(long bookingsThisWeek, BigDecimal revenueProjected) {
    }
}
