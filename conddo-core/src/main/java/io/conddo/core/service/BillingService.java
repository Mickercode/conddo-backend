package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PlanFeature;
import io.conddo.core.domain.SubscriptionPlan;
import io.conddo.core.domain.TenantSubscription;
import io.conddo.core.repository.PlanFeatureRepository;
import io.conddo.core.repository.SubscriptionPlanRepository;
import io.conddo.core.repository.TenantSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing orchestrator (BILLING_TIERS_SPEC §1-7). Owns the plan catalog,
 * tenant subscription lifecycle, and feature gating.
 *
 * <p>Bypasses RLS deliberately — billing decisions need to see every
 * subscription per tenant including historical, and the JWT-mint path needs
 * to read state without a bound tenant. Treat this service the same way
 * {@code TenantService} treats {@code tenants}.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final String DEFAULT_PLAN = "launcher";
    private static final String FALLBACK_TIER = "starter";

    /** Spec §1: 14-day trial on new tenant signup. */
    private static final int TRIAL_DAYS = 14;

    /** Map {@code launcher/growth/scaler} → the internal {@code starter/business/pro} tier
     *  the existing {@link io.conddo.core.registry.VerticalToolMatrix} keys on. */
    private static final Map<String, String> PLAN_TO_TIER = Map.of(
            "launcher", "starter",
            "growth",   "business",
            "scaler",   "pro");

    private final SubscriptionPlanRepository planRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final PlanFeatureRepository featureRepository;
    private final Clock clock;
    private final int gracePeriodDays;

    public BillingService(SubscriptionPlanRepository planRepository,
                          TenantSubscriptionRepository subscriptionRepository,
                          PlanFeatureRepository featureRepository,
                          Clock clock,
                          @Value("${conddo.billing.grace-period-days:3}") int gracePeriodDays) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.featureRepository = featureRepository;
        this.clock = clock;
        this.gracePeriodDays = gracePeriodDays;
    }

    // ----- catalog (public) --------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlanWithFeatures> catalog() {
        return planRepository.findByActiveTrueOrderByMonthlyPriceAsc().stream()
                .map(p -> new PlanWithFeatures(p, featuresFor(p.getId())))
                .toList();
    }

    /** Plan by name (e.g. {@code "launcher"}); 404 if unknown. */
    @Transactional(readOnly = true)
    public PlanWithFeatures plan(String name) {
        SubscriptionPlan plan = planRepository.findByName(normalisePlanName(name))
                .orElseThrow(() -> new NotFoundException("Plan not found: " + name));
        return new PlanWithFeatures(plan, featuresFor(plan.getId()));
    }

    /** Translate canonical plan name ({@code launcher/growth/scaler}) → tier ({@code starter/business/pro}). */
    public String tierForPlan(String planName) {
        if (planName == null) {
            return FALLBACK_TIER;
        }
        return PLAN_TO_TIER.getOrDefault(planName.trim().toLowerCase(), FALLBACK_TIER);
    }

    // ----- subscription lifecycle --------------------------------------------

    /**
     * Spec §3 — called from signup ({@code RegistrationService.complete} or
     * {@code TenantService.create}). Idempotent: if the tenant already has a
     * live subscription, return that instead of creating a duplicate.
     */
    @Transactional
    public TenantSubscription createTrialForNewTenant(UUID tenantId, String planName) {
        Optional<TenantSubscription> existing = subscriptionRepository.findActiveByTenantId(tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        SubscriptionPlan plan = planRepository.findByName(normalisePlanName(planName))
                .orElseGet(() -> planRepository.findByName(DEFAULT_PLAN)
                        .orElseThrow(() -> new IllegalStateException("Catalog not seeded — no default plan")));
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime trialEnds = now.plusDays(TRIAL_DAYS);
        TenantSubscription sub = new TenantSubscription(tenantId, plan.getId(), "monthly",
                "trialing", now, trialEnds, trialEnds);
        return subscriptionRepository.save(sub);
    }

    /** Current live subscription — null if none. Caller checks for absence. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionWithPlan> getActiveSubscription(UUID tenantId) {
        return subscriptionRepository.findActiveByTenantId(tenantId)
                .map(sub -> new SubscriptionWithPlan(sub, planRepository.findById(sub.getPlanId()).orElse(null)));
    }

    /**
     * Spec §4 + §6 — upgrade / downgrade lands on a single new active row.
     * Upgrades take effect immediately (the new row inherits remaining time on
     * the old expires_at); downgrades take effect at end-of-period (the old
     * row remains active until expires_at, the new row's start is queued —
     * Phase 1 simplifies to "same as upgrade" since we don't yet do paid
     * downgrades).
     */
    @Transactional
    public TenantSubscription upgrade(UUID tenantId, String planName, String billingCycle) {
        SubscriptionPlan target = planRepository.findByName(normalisePlanName(planName))
                .orElseThrow(() -> new NotFoundException("Plan not found: " + planName));
        TenantSubscription current = subscriptionRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active subscription to upgrade for tenant " + tenantId));
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Carry over remaining time so the upgrade isn't a downgrade in disguise.
        OffsetDateTime newExpiry = now.isAfter(current.getExpiresAt())
                ? now.plus(billingCycle.equals("quarterly") ? 90 : 30, ChronoUnit.DAYS)
                : current.getExpiresAt();
        // Cancel the old row, insert the new active row.
        current.cancel(now);
        current.completeCancellation();
        subscriptionRepository.save(current);

        TenantSubscription replacement = new TenantSubscription(tenantId, target.getId(),
                billingCycle == null ? "monthly" : billingCycle, "active", now, newExpiry, null);
        return subscriptionRepository.save(replacement);
    }

    /** Spec §4 — soft cancel; access continues until {@code expires_at}. */
    @Transactional
    public TenantSubscription cancel(UUID tenantId) {
        TenantSubscription sub = subscriptionRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No active subscription to cancel for tenant " + tenantId));
        sub.cancel(OffsetDateTime.now(clock));
        return subscriptionRepository.save(sub);
    }

    // ----- gating ------------------------------------------------------------

    /**
     * Spec §5 — does this tenant's plan unlock {@code featureKey}? Treats
     * unknown plans / expired subscriptions / missing features as <b>false</b>
     * — fail-closed in line with the "Conddo never silently lets paid
     * features bleed" rule.
     */
    @Transactional(readOnly = true)
    public boolean hasFeature(UUID tenantId, String featureKey) {
        Optional<TenantSubscription> sub = subscriptionRepository.findActiveByTenantId(tenantId);
        if (sub.isEmpty()) {
            return false;
        }
        TenantSubscription s = sub.get();
        if ("expired".equals(s.getStatus()) || "cancelled".equals(s.getStatus())) {
            return false;
        }
        return featureRepository.findByPlanIdAndFeatureKey(s.getPlanId(), featureKey)
                .map(PlanFeature::getFeatureValue)
                .map(v -> "true".equalsIgnoreCase(v))
                .orElse(false);
    }

    /**
     * Numeric / sentinel feature read — returns {@code Integer.MAX_VALUE} for
     * {@code "unlimited"}, the parsed integer otherwise, or {@code 0} when the
     * feature isn't set. Used by the staff-invite count gate.
     */
    @Transactional(readOnly = true)
    public int featureLimit(UUID tenantId, String featureKey) {
        Optional<TenantSubscription> sub = subscriptionRepository.findActiveByTenantId(tenantId);
        if (sub.isEmpty()) {
            return 0;
        }
        return featureRepository.findByPlanIdAndFeatureKey(sub.get().getPlanId(), featureKey)
                .map(PlanFeature::getFeatureValue)
                .map(BillingService::parseLimit)
                .orElse(0);
    }

    /**
     * Spec §6 — apply state transitions if {@code expires_at} has passed. Run
     * lazily from the JWT-mint and manifest-resolve paths; the Phase-2 cron
     * does the same proactively.
     */
    @Transactional
    public TenantSubscription applyExpiryTransitions(TenantSubscription sub) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isAfter(sub.getExpiresAt())) {
            return sub;
        }
        if ("trialing".equals(sub.getStatus()) || "active".equals(sub.getStatus())) {
            sub.enterGrace();
            return subscriptionRepository.save(sub);
        }
        if ("grace".equals(sub.getStatus())
                && now.isAfter(sub.getExpiresAt().plusDays(gracePeriodDays))) {
            sub.expire();
            return subscriptionRepository.save(sub);
        }
        if (sub.getCancelledAt() != null
                && now.isAfter(sub.getExpiresAt())
                && !"cancelled".equals(sub.getStatus())) {
            sub.completeCancellation();
            return subscriptionRepository.save(sub);
        }
        return sub;
    }

    // ----- helpers -----------------------------------------------------------

    /** All features for a plan as a {@code key → value} map (the wire shape's {@code features}). */
    @Transactional(readOnly = true)
    public Map<String, String> featuresFor(UUID planId) {
        Map<String, String> out = new HashMap<>();
        for (PlanFeature f : featureRepository.findByPlanId(planId)) {
            out.put(f.getFeatureKey(), f.getFeatureValue());
        }
        return out;
    }

    private static String normalisePlanName(String planName) {
        return planName == null ? DEFAULT_PLAN : planName.trim().toLowerCase();
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return 0;
        }
        if ("unlimited".equalsIgnoreCase(value)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ----- records -----------------------------------------------------------

    public record PlanWithFeatures(SubscriptionPlan plan, Map<String, String> features) {
    }

    public record SubscriptionWithPlan(TenantSubscription subscription, SubscriptionPlan plan) {
    }
}
