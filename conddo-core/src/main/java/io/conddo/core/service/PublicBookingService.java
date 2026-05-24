package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Booking;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.BookingRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PUBLIC, unauthenticated client-facing self-book flow (§11.5). There is no
 * JWT, so the tenant is resolved from the link slug against the (non-RLS)
 * tenants table, then bound onto {@link TenantContext} so RLS scopes the
 * booking writes/reads exactly as for an authenticated request. A disabled link
 * resolves to 404. Self-bookings land as {@code pending} for the owner to confirm.
 */
@Service
public class PublicBookingService {

    private static final String CANCELLED = "cancelled";

    private final TenantRepository tenantRepository;
    private final BookingRepository bookingRepository;
    private final NotificationFeedService notificationFeedService;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PublicBookingService(TenantRepository tenantRepository, BookingRepository bookingRepository,
                                NotificationFeedService notificationFeedService,
                                TenantSession tenantSession, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.bookingRepository = bookingRepository;
        this.notificationFeedService = notificationFeedService;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    /** The business's public availability + already-booked slots over the next two weeks. */
    @Transactional(readOnly = true)
    public PublicAvailability availability(String slug) {
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Slot> booked = bookingRepository
                .findByStartsAtBetweenAndStatusNotOrderByStartsAt(now, now.plusDays(14), CANCELLED)
                .stream().map(b -> new Slot(b.getStartsAt(), b.getEndsAt())).toList();
        Map<String, Object> hours = tenant.getWorkingHours() != null
                ? tenant.getWorkingHours() : BookingService.defaultWorkingHours();
        return new PublicAvailability(tenant.getName(), hours,
                tenant.getSlotDurationMinutes(), tenant.getBufferMinutes(), booked);
    }

    /** Creates a pending booking from the public page; the owner confirms later. */
    @Transactional
    public Booking book(String slug, String customerName, String phone, String service, OffsetDateTime start) {
        Tenant tenant = resolve(slug);
        TenantContext.set(tenant.getId());
        tenantSession.bind();
        OffsetDateTime end = start.plusMinutes(tenant.getSlotDurationMinutes());
        Booking booking = new Booking(tenant.getId(), null, customerName, service, start, end, "in_person", "pending");
        booking.setNotes(phone == null || phone.isBlank()
                ? "Self-booked via link" : "Self-booked via link. Contact: " + phone);
        booking = bookingRepository.save(booking);

        // Notify the owner of the incoming request (§11.12 bell feed).
        notificationFeedService.create("BOOKING", "New booking request",
                customerName + (service == null ? "" : " — " + service), null);
        return booking;
    }

    private Tenant resolve(String slug) {
        Tenant tenant = tenantRepository.findByBookingLinkSlug(slug)
                .or(() -> tenantRepository.findBySlug(slug))
                .orElseThrow(() -> new NotFoundException("Booking page not found"));
        if (!tenant.isBookingLinkEnabled()) {
            throw new NotFoundException("Booking page not found");
        }
        return tenant;
    }

    /** A booked time slot — exposed publicly without any customer details. */
    public record Slot(OffsetDateTime start, OffsetDateTime end) {
    }

    /** Public availability: business name, hours, slot/buffer, and booked slots. */
    public record PublicAvailability(String business, Map<String, Object> workingHours,
                                     int slotDurationMinutes, int bufferMinutes, List<Slot> booked) {
    }
}
