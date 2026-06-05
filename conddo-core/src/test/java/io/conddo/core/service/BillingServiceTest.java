package io.conddo.core.service;

import io.conddo.core.domain.PlanFeature;
import io.conddo.core.domain.SubscriptionPlan;
import io.conddo.core.domain.TenantSubscription;
import io.conddo.core.repository.PlanFeatureRepository;
import io.conddo.core.repository.SubscriptionPlanRepository;
import io.conddo.core.repository.TenantSubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins BILLING_TIERS_SPEC §3-7 contracts: trial creation, feature gating,
 * plan→tier translation, and the grace/expired state transitions.
 */
class BillingServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LAUNCHER_ID = UUID.randomUUID();
    private static final UUID GROWTH_ID = UUID.randomUUID();

    private final SubscriptionPlanRepository planRepository = mock(SubscriptionPlanRepository.class);
    private final TenantSubscriptionRepository subscriptionRepository = mock(TenantSubscriptionRepository.class);
    private final PlanFeatureRepository featureRepository = mock(PlanFeatureRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final BillingService service = new BillingService(planRepository, subscriptionRepository,
            featureRepository, clock, 3);

    @Test
    void tierForPlanTranslatesProductNamesToTierNames() {
        assertEquals("starter",  service.tierForPlan("launcher"));
        assertEquals("business", service.tierForPlan("growth"));
        assertEquals("pro",      service.tierForPlan("scaler"));
        // Unknown / null → safe default.
        assertEquals("starter",  service.tierForPlan(null));
        assertEquals("starter",  service.tierForPlan("unknown"));
        // Case-insensitive.
        assertEquals("business", service.tierForPlan("GROWTH"));
    }

    @Test
    void createTrialForNewTenantStampsTrialingAndFourteenDayExpiry() {
        when(planRepository.findByName("launcher")).thenReturn(Optional.of(plan("launcher", LAUNCHER_ID)));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(TenantSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantSubscription sub = service.createTrialForNewTenant(TENANT, "launcher");

        assertEquals("trialing", sub.getStatus());
        assertEquals("monthly", sub.getBillingCycle());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(14), sub.getExpiresAt());
        assertEquals(sub.getExpiresAt(), sub.getTrialEndsAt());
    }

    @Test
    void createTrialIsIdempotent() {
        TenantSubscription existing = trialingSub(LAUNCHER_ID, NOW, NOW.plusSeconds(86_400 * 7));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.of(existing));

        TenantSubscription result = service.createTrialForNewTenant(TENANT, "launcher");
        assertEquals(existing, result);
    }

    @Test
    void createTrialFallsBackToLauncherOnUnknownPlanName() {
        when(planRepository.findByName("nonsense")).thenReturn(Optional.empty());
        when(planRepository.findByName("launcher")).thenReturn(Optional.of(plan("launcher", LAUNCHER_ID)));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.empty());
        when(subscriptionRepository.save(any(TenantSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantSubscription sub = service.createTrialForNewTenant(TENANT, "nonsense");
        assertEquals(LAUNCHER_ID, sub.getPlanId());
    }

    @Test
    void hasFeatureIsTrueOnLiveSubscriptionWithFeature() {
        TenantSubscription sub = trialingSub(GROWTH_ID, NOW, NOW.plusSeconds(86_400 * 7));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.of(sub));
        when(featureRepository.findByPlanIdAndFeatureKey(GROWTH_ID, "ad_management"))
                .thenReturn(Optional.of(feature(GROWTH_ID, "ad_management", "true")));

        assertTrue(service.hasFeature(TENANT, "ad_management"));
    }

    @Test
    void hasFeatureIsFalseOnFeatureFlaggedFalse() {
        TenantSubscription sub = trialingSub(LAUNCHER_ID, NOW, NOW.plusSeconds(86_400 * 7));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.of(sub));
        when(featureRepository.findByPlanIdAndFeatureKey(LAUNCHER_ID, "ad_management"))
                .thenReturn(Optional.of(feature(LAUNCHER_ID, "ad_management", "false")));

        assertFalse(service.hasFeature(TENANT, "ad_management"));
    }

    @Test
    void hasFeatureIsFalseOnExpiredSubscription() {
        TenantSubscription sub = trialingSub(GROWTH_ID, NOW.minusSeconds(86_400 * 30), NOW.minusSeconds(86_400));
        // Force status to expired (active query would skip it but the
        // explicit state check covers it).
        sub.expire();
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.empty());

        assertFalse(service.hasFeature(TENANT, "ad_management"));
    }

    @Test
    void featureLimitParsesIntegersAndUnlimitedSentinel() {
        TenantSubscription sub = trialingSub(LAUNCHER_ID, NOW, NOW.plusSeconds(86_400 * 7));
        when(subscriptionRepository.findActiveByTenantId(TENANT)).thenReturn(Optional.of(sub));
        when(featureRepository.findByPlanIdAndFeatureKey(LAUNCHER_ID, "staff_accounts"))
                .thenReturn(Optional.of(feature(LAUNCHER_ID, "staff_accounts", "2")));
        assertEquals(2, service.featureLimit(TENANT, "staff_accounts"));

        when(featureRepository.findByPlanIdAndFeatureKey(LAUNCHER_ID, "staff_accounts"))
                .thenReturn(Optional.of(feature(LAUNCHER_ID, "staff_accounts", "unlimited")));
        assertEquals(Integer.MAX_VALUE, service.featureLimit(TENANT, "staff_accounts"));
    }

    @Test
    void applyExpiryTransitionsFlipsTrialingPastExpiryToGrace() {
        TenantSubscription sub = trialingSub(LAUNCHER_ID, NOW.minusSeconds(86_400 * 15),
                NOW.minusSeconds(86_400));
        when(subscriptionRepository.save(sub)).thenReturn(sub);

        TenantSubscription updated = service.applyExpiryTransitions(sub);
        assertEquals("grace", updated.getStatus());
    }

    @Test
    void applyExpiryTransitionsFlipsGracePastGraceDaysToExpired() {
        TenantSubscription sub = trialingSub(LAUNCHER_ID, NOW.minusSeconds(86_400 * 20),
                NOW.minusSeconds(86_400 * 7));   // expired 7 days ago
        sub.enterGrace();
        when(subscriptionRepository.save(sub)).thenReturn(sub);

        TenantSubscription updated = service.applyExpiryTransitions(sub);
        assertEquals("expired", updated.getStatus());
    }

    @Test
    void applyExpiryTransitionsIsNoopWhenNotYetExpired() {
        TenantSubscription sub = trialingSub(LAUNCHER_ID, NOW, NOW.plusSeconds(86_400));
        TenantSubscription result = service.applyExpiryTransitions(sub);
        assertEquals("trialing", result.getStatus());
    }

    @Test
    void catalogReturnsPlansSortedByMonthlyPrice() {
        when(planRepository.findByActiveTrueOrderByMonthlyPriceAsc()).thenReturn(List.of(
                plan("launcher", LAUNCHER_ID), plan("growth", GROWTH_ID)));
        when(featureRepository.findByPlanId(LAUNCHER_ID)).thenReturn(List.of(
                feature(LAUNCHER_ID, "website", "true")));
        when(featureRepository.findByPlanId(GROWTH_ID)).thenReturn(List.of(
                feature(GROWTH_ID, "website", "true"), feature(GROWTH_ID, "bookings", "true")));

        var catalog = service.catalog();
        assertEquals(2, catalog.size());
        assertEquals("launcher", catalog.get(0).plan().getName());
        assertEquals("true", catalog.get(1).features().get("bookings"));
    }

    // ----- helpers -----------------------------------------------------------

    private static SubscriptionPlan plan(String name, UUID id) {
        SubscriptionPlan p = newInstance(SubscriptionPlan.class);
        setField(SubscriptionPlan.class, p, "id", id);
        setField(SubscriptionPlan.class, p, "name", name);
        setField(SubscriptionPlan.class, p, "displayName", name.substring(0, 1).toUpperCase() + name.substring(1));
        return p;
    }

    private static PlanFeature feature(UUID planId, String key, String value) {
        PlanFeature f = newInstance(PlanFeature.class);
        setField(PlanFeature.class, f, "planId", planId);
        setField(PlanFeature.class, f, "featureKey", key);
        setField(PlanFeature.class, f, "featureValue", value);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (T) ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TenantSubscription trialingSub(UUID planId, Instant start, Instant expires) {
        TenantSubscription sub = new TenantSubscription(TENANT, planId, "monthly", "trialing",
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(expires, ZoneOffset.UTC));
        setField(TenantSubscription.class, sub, "id", UUID.randomUUID());
        return sub;
    }

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
