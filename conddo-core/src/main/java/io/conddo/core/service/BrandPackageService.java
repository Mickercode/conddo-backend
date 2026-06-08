package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.BrandPackageOffering;
import io.conddo.core.domain.BrandPackageSubscription;
import io.conddo.core.domain.BrandPackageUsage;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.payments.PaymentsGateway;
import io.conddo.core.repository.BrandPackageOfferingRepository;
import io.conddo.core.repository.BrandPackageSubscriptionRepository;
import io.conddo.core.repository.BrandPackageUsageRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the brand-package lifecycle
 * (SOCIAL_AND_CREATIVE_SERVICES_SPEC §6):
 *
 * <ol>
 *   <li>{@link #subscribe} — tenant picks an offering, the row is created
 *       {@code pending_payment}, {@link PaymentsGateway} returns a hosted
 *       checkout URL. On RoutePay success the existing internal payments
 *       webhook routes via {@link #handlePaymentPaid}.</li>
 *   <li>{@link #handlePaymentPaid} — flips subscription to {@code active},
 *       opens the first usage period. Idempotent.</li>
 *   <li>{@link #checkAndConsume} — called by {@code CreativeServiceService}
 *       before charging per-job: if the tenant has an active subscription
 *       and the offering's code is included with quota remaining, increment
 *       the usage counter and return {@code true} (the request rides on
 *       the subscription, {@code price_kobo = 0}, no payment).</li>
 *   <li>{@link #cancel} — flips to {@code cancelled} (access continues
 *       until {@code current_period_end}; no refund).</li>
 * </ol>
 *
 * <p>The renewal cron lives in
 * {@code conddo-api}'s {@code BrandPackageRenewalScheduler} (TODO Phase 3b).
 */
@Service
public class BrandPackageService {

    private static final Logger log = LoggerFactory.getLogger(BrandPackageService.class);

    private final BrandPackageOfferingRepository offeringRepository;
    private final BrandPackageSubscriptionRepository subscriptionRepository;
    private final BrandPackageUsageRepository usageRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PaymentsGateway paymentsGateway;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final String appBaseUrl;

    @PersistenceContext
    private EntityManager entityManager;

    public BrandPackageService(BrandPackageOfferingRepository offeringRepository,
                               BrandPackageSubscriptionRepository subscriptionRepository,
                               BrandPackageUsageRepository usageRepository,
                               TenantRepository tenantRepository,
                               UserRepository userRepository,
                               PaymentsGateway paymentsGateway,
                               TenantSession tenantSession,
                               Clock clock,
                               @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl) {
        this.offeringRepository = offeringRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usageRepository = usageRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.paymentsGateway = paymentsGateway;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.appBaseUrl = appBaseUrl;
    }

    // ----- catalog -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<BrandPackageOffering> catalog() {
        return offeringRepository.findByActiveTrueOrderByMonthlyPriceKoboAsc();
    }

    @Transactional(readOnly = true)
    public Optional<CurrentView> currentSubscription() {
        tenantSession.bind();
        return subscriptionRepository.findCurrent()
                .map(s -> new CurrentView(s, offeringRepository.findById(s.getOfferingId()).orElse(null)));
    }

    /**
     * Current-period usage snapshot for the calling tenant. Returns empty
     * when the tenant has no subscription (FE renders zero bars) or when a
     * brand-new subscription hasn't consumed quota yet (FE renders zero
     * bars against the offering's {@code includes} ceilings).
     */
    @Transactional(readOnly = true)
    public Optional<UsageSnapshot> currentUsage() {
        tenantSession.bind();
        return subscriptionRepository.findCurrent()
                .map(sub -> usageRepository.findFirstBySubscriptionIdOrderByPeriodStartDesc(sub.getId())
                        .map(u -> new UsageSnapshot(u.getCounts(), u.getPeriodStart(), u.getPeriodEnd()))
                        .orElseGet(() -> new UsageSnapshot(java.util.Map.of(),
                                sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd())));
    }

    /** Current-period usage view returned by {@link #currentUsage}. */
    public record UsageSnapshot(java.util.Map<String, Integer> counts,
                                OffsetDateTime periodStart,
                                OffsetDateTime periodEnd) {
    }

    // ----- subscribe ---------------------------------------------------------

    @Transactional
    public SubscribeResult subscribe(UUID userId, String offeringCode) {
        tenantSession.bind();
        if (offeringCode == null || offeringCode.isBlank()) {
            throw new IllegalArgumentException("offeringCode is required");
        }
        // Reject if the tenant already has a live subscription — they should
        // change tiers via a separate upgrade flow (Phase 3b).
        subscriptionRepository.findCurrent().ifPresent(s -> {
            throw new AlreadySubscribedException(
                    "Tenant already has a " + s.getStatus() + " brand-package subscription");
        });
        BrandPackageOffering offering = offeringRepository.findByCode(offeringCode.trim())
                .filter(BrandPackageOffering::isActive)
                .orElseThrow(() -> new NotFoundException(
                        "Unknown brand-package offering: " + offeringCode));

        OffsetDateTime now = OffsetDateTime.now(clock);
        BrandPackageSubscription subscription = new BrandPackageSubscription(
                TenantContext.require(), offering.getId(), now, now.plusMonths(1));
        subscription = subscriptionRepository.save(subscription);

        String checkoutUrl = initCheckout(subscription, offering, userId)
                .orElseThrow(() -> new PaymentsUnavailableException(
                        "Payments service is unreachable — please retry in a moment."));
        return new SubscribeResult(subscription, offering, checkoutUrl);
    }

    // ----- webhook: payment paid --------------------------------------------

    @Transactional
    public void handlePaymentPaid(String paymentReference) {
        if (paymentReference == null || paymentReference.isBlank()) {
            return;
        }
        crossTenantBypass();
        BrandPackageSubscription sub = subscriptionRepository
                .findByPaymentReference(paymentReference).orElse(null);
        if (sub == null) {
            log.warn("Brand-package paid webhook referenced unknown reference {}", paymentReference);
            return;
        }
        if (BrandPackageSubscription.STATUS_ACTIVE.equals(sub.getStatus())) {
            return;   // idempotent
        }
        TenantContext.set(sub.getTenantId());
        tenantSession.bind();
        sub.markPaid(paymentReference);
        subscriptionRepository.save(sub);
        // Seed the first usage row for the period if not present.
        usageRepository.findBySubscriptionIdAndPeriodStart(sub.getId(), sub.getCurrentPeriodStart())
                .orElseGet(() -> usageRepository.save(new BrandPackageUsage(
                        sub.getTenantId(), sub.getId(),
                        sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd())));
    }

    // ----- creative-service integration --------------------------------------

    /**
     * Try to consume one slot from the tenant's active brand-package for the
     * given creative-service {@code offeringCode}. Returns true on success
     * (caller skips the per-job charge). False when there's no active
     * subscription or the package doesn't include this code. Throws
     * {@link QuotaExhaustedException} when included but used up — caller
     * surfaces 409 to the FE so the tenant can decide to pay-per-job or wait.
     */
    @Transactional
    public boolean checkAndConsume(String offeringCode) {
        tenantSession.bind();
        BrandPackageSubscription sub = subscriptionRepository.findCurrent().orElse(null);
        if (sub == null || !BrandPackageSubscription.STATUS_ACTIVE.equals(sub.getStatus())) {
            return false;
        }
        BrandPackageOffering offering = offeringRepository.findById(sub.getOfferingId()).orElse(null);
        if (offering == null) {
            return false;
        }
        Integer included = offering.getIncludes() == null ? null : offering.getIncludes().get(offeringCode);
        if (included == null || included <= 0) {
            return false;   // this code isn't part of the package; caller pays per-job
        }

        BrandPackageUsage usage = usageRepository
                .findBySubscriptionIdAndPeriodStart(sub.getId(), sub.getCurrentPeriodStart())
                .orElseGet(() -> usageRepository.save(new BrandPackageUsage(
                        sub.getTenantId(), sub.getId(),
                        sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd())));
        if (usage.countFor(offeringCode) >= included) {
            throw new QuotaExhaustedException(offeringCode, included);
        }
        usage.increment(offeringCode);
        usageRepository.save(usage);
        return true;
    }

    // ----- cancel ------------------------------------------------------------

    /**
     * Mark cancelled. The current period's quota stays usable until
     * {@code current_period_end} (the spec wording — "tenant subscribes
     * to a recurring bundle" — implies no immediate revoke; renewal just
     * doesn't fire).
     */
    @Transactional
    public BrandPackageSubscription cancel() {
        tenantSession.bind();
        BrandPackageSubscription sub = subscriptionRepository.findCurrent()
                .orElseThrow(() -> new NotFoundException("No brand-package subscription to cancel"));
        sub.cancel(OffsetDateTime.now(clock));
        return subscriptionRepository.save(sub);
    }

    // ----- helpers -----------------------------------------------------------

    private Optional<String> initCheckout(BrandPackageSubscription subscription,
                                          BrandPackageOffering offering,
                                          UUID userId) {
        Tenant tenant = tenantRepository.findById(subscription.getTenantId()).orElse(null);
        User user = userRepository.findById(userId).orElse(null);
        String tenantSlug = tenant == null ? null : tenant.getSlug();
        String userEmail = user == null ? null : user.getEmail();
        String userName = user == null ? null : user.getFullName();
        String returnUrl = appBaseUrl + "/marketing/brand-packages?subscription=" + subscription.getId();
        String description = "Conddo Brand Package — " + offering.getName();

        Optional<PaymentsGateway.PaymentInitResult> init = paymentsGateway.initBrandPackageCharge(
                subscription.getTenantId(), tenantSlug, subscription.getId(), userId,
                userEmail, userName, offering.getMonthlyPriceKobo(), description, returnUrl);
        if (init.isEmpty()) {
            return Optional.empty();
        }
        subscription.setPaymentReference(init.get().reference());
        return Optional.ofNullable(init.get().paymentUrl());
    }

    private void crossTenantBypass() {
        entityManager.createNativeQuery("SELECT set_config('app.public_resolver', 'true', true)")
                .getSingleResult();
    }

    // ----- result records + exceptions --------------------------------------

    public record SubscribeResult(BrandPackageSubscription subscription, BrandPackageOffering offering,
                                  String checkoutUrl) {
    }

    public record CurrentView(BrandPackageSubscription subscription, BrandPackageOffering offering) {
    }

    public static class AlreadySubscribedException extends RuntimeException {
        public AlreadySubscribedException(String msg) {
            super(msg);
        }
    }

    public static class PaymentsUnavailableException extends RuntimeException {
        public PaymentsUnavailableException(String msg) {
            super(msg);
        }
    }

    public static class QuotaExhaustedException extends RuntimeException {
        private final String offeringCode;
        private final int quota;

        public QuotaExhaustedException(String offeringCode, int quota) {
            super("Brand-package quota for " + offeringCode + " is exhausted (" + quota + " used).");
            this.offeringCode = offeringCode;
            this.quota = quota;
        }

        public String getOfferingCode() {
            return offeringCode;
        }

        public int getQuota() {
            return quota;
        }
    }
}
