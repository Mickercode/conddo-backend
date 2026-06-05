package io.conddo.api.billing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Plan-feature gate (BILLING_TIERS_SPEC §5). Annotate a controller method with
 * a {@code feature_key} (e.g. {@code "ad_management"}) and the request is
 * blocked with 403 {@code PLAN_UPGRADE_REQUIRED} when the calling tenant's
 * plan doesn't unlock it.
 *
 * <p>Resolved by {@link RequiresFeatureInterceptor}, which reads the bound
 * {@code TenantContext} for the tenant id and asks {@link io.conddo.core.service.BillingService}
 * whether the feature is on. Class-level annotation applies to every method
 * unless a method-level annotation overrides it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFeature {

    /** The {@code plan_features.feature_key} to check (e.g. {@code "ad_management"}). */
    String value();

    /** Human plan name surfaced in the 403 body (e.g. {@code "Growth"}). */
    String requiredPlan() default "Growth";

    /** Required plan monthly price in Naira, surfaced in the 403 body for the upgrade CTA. */
    int requiredPlanPrice() default 45000;
}
