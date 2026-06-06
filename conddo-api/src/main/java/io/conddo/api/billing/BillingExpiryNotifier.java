package io.conddo.api.billing;

import io.conddo.core.domain.SubscriptionPlan;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.notify.NotificationService;
import io.conddo.core.repository.SubscriptionPlanRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.service.BillingService.TransitionResult;
import io.conddo.core.service.NotificationFeedService;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Fan-out half of the hourly expiry cron (merchant-readiness slice 3). Owns
 * the bell-feed + email + SMS dispatch for a single subscription transition.
 *
 * <p>Lives in its own component so the {@link BillingExpiryScheduler} can
 * invoke it through the Spring proxy — a {@code @Transactional} method on
 * the same bean as the scheduler would self-invoke and miss the
 * {@code REQUIRES_NEW} boundary, leaving the {@code tenantSession.bind()}
 * call running outside any transaction.
 */
@Component
public class BillingExpiryNotifier {

    private final SubscriptionPlanRepository planRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final NotificationFeedService notificationFeedService;
    private final NotificationService notificationService;
    private final TenantSession tenantSession;
    private final int gracePeriodDays;

    public BillingExpiryNotifier(SubscriptionPlanRepository planRepository,
                                 TenantRepository tenantRepository,
                                 UserRepository userRepository,
                                 NotificationFeedService notificationFeedService,
                                 NotificationService notificationService,
                                 TenantSession tenantSession,
                                 @Value("${conddo.billing.grace-period-days:3}") int gracePeriodDays) {
        this.planRepository = planRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.notificationFeedService = notificationFeedService;
        this.notificationService = notificationService;
        this.tenantSession = tenantSession;
        this.gracePeriodDays = gracePeriodDays;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(TransitionResult t) {
        if (!"grace".equals(t.toStatus()) && !"expired".equals(t.toStatus())) {
            // Cancelled-completion is silent (merchant initiated it).
            return;
        }
        try {
            TenantContext.set(t.tenantId());
            tenantSession.bind();

            Tenant tenant = tenantRepository.findById(t.tenantId()).orElse(null);
            if (tenant == null) {
                return;
            }
            Optional<User> owner = userRepository.findFirstByRoleOrderByCreatedAtAsc("TENANT_ADMIN");
            String planName = planRepository.findById(t.planId())
                    .map(SubscriptionPlan::getName)
                    .orElse("plan");

            boolean expired = "expired".equals(t.toStatus());
            String type = expired ? "PLAN_EXPIRED" : "PLAN_GRACE";
            String title = expired
                    ? "Subscription expired"
                    : "Trial ended — grace period started";
            String body = expired
                    ? "Your " + planName + " subscription has expired. Reactivate to keep your features."
                    : "Your " + planName + " trial just ended. You have " + gracePeriodDays
                            + " days to add a payment method before access pauses.";
            notificationFeedService.create(type, title, body,
                    owner.map(User::getId).orElse(null));

            String email = firstNonBlank(owner.map(User::getEmail).orElse(null), tenant.getContactEmail());
            String phone = firstNonBlank(owner.map(User::getPhone).orElse(null), tenant.getContactPhone());
            notificationService.sendPlanTransition(
                    email, phone, tenant.getName(), planName, t.toStatus(), gracePeriodDays);
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
}
