package io.conddo.api.publicapi;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.events.BookingCreatedEvent;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.NotificationFeedService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Booking-source notify parity with the order flow
 * ({@link OrderNotificationListener}). Fans out the merchant nudge
 * to the in-app bell feed + the owner's email + the owner's SMS when
 * a customer self-books on the merchant's public booking link.
 *
 * <p>Runs <b>after commit</b> in a fresh transaction — a rolled-back
 * booking never notifies, and a flaky email/SMS provider never bubbles
 * to the customer who just submitted the request. Dashboard-typed
 * bookings stay silent (the merchant is already on the screen).
 */
@Component
public class BookingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(BookingNotificationListener.class);

    private static final DateTimeFormatter WHEN_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final NotificationFeedService notificationFeedService;
    private final NotificationService notificationService;
    private final TenantSession tenantSession;

    public BookingNotificationListener(TenantRepository tenantRepository,
                                       UserRepository userRepository,
                                       NotificationFeedService notificationFeedService,
                                       NotificationService notificationService,
                                       TenantSession tenantSession) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.notificationFeedService = notificationFeedService;
        this.notificationService = notificationService;
        this.tenantSession = tenantSession;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingCreated(BookingCreatedEvent event) {
        if (event.source() != BookingCreatedEvent.Source.PUBLIC_WEBSITE) {
            return;
        }
        try {
            TenantContext.set(event.tenantId());
            tenantSession.bind();

            Tenant tenant = tenantRepository.findById(event.tenantId()).orElse(null);
            if (tenant == null) {
                return;
            }
            Optional<User> owner = userRepository.findFirstByRoleOrderByCreatedAtAsc("TENANT_ADMIN");

            String whenStr = event.startsAt() == null
                    ? null : WHEN_FORMAT.format(event.startsAt());
            String title = "New booking request";
            String body = event.customerName()
                    + (event.service() == null || event.service().isBlank()
                            ? "" : " — " + event.service())
                    + (whenStr == null ? "" : " · " + whenStr);
            notificationFeedService.create("BOOKING", title, body,
                    owner.map(User::getId).orElse(null));

            // Email/SMS fall back to the tenant's business contacts when the
            // owner user has neither on their own profile — same pattern as
            // the order notify flow.
            String email = firstNonBlank(
                    owner.map(User::getEmail).orElse(null),
                    tenant.getContactEmail());
            String phone = firstNonBlank(
                    owner.map(User::getPhone).orElse(null),
                    tenant.getContactPhone());
            notificationService.sendBookingAlert(email, phone, tenant.getName(),
                    event.customerName(), event.service(), whenStr, event.contactPhone());
        } catch (RuntimeException ex) {
            log.error("Booking notification failed for tenant {} booking {}: {}",
                    event.tenantId(), event.bookingId(), ex.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    /** Silence the unused-import warning when only the formatter is referenced. */
    @SuppressWarnings("unused")
    private static OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
