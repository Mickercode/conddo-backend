package io.conddo.api.features;

import io.conddo.core.features.FeatureNotEnabledException;
import io.conddo.core.service.TenantFeatureFlagService;
import io.conddo.core.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Spring bean exposed for SpEL access inside {@code @PreAuthorize}.
 * Wires the FE's expected per-endpoint gating shape
 * (HANDOFF_2026-06-11 §0):
 *
 * <pre>
 * &#64;PreAuthorize("&#64;featureFlagGuard.requiresFlag('cashback_loyalty')")
 * &#64;RestController &#64;RequestMapping("/api/v1/pharmacy/loyalty")
 * public class PharmacyLoyaltyController { ... }
 * </pre>
 *
 * <p>On miss, throws {@link FeatureNotEnabledException} so the global
 * handler returns the structured 403 envelope the FE BetaFeatureGate
 * component expects. Returning {@code false} would surface as a plain
 * FORBIDDEN without the {@code featureKey} field hint.
 */
@Component("featureFlagGuard")
public class FeatureFlagGuard {

    private final TenantFeatureFlagService service;

    public FeatureFlagGuard(TenantFeatureFlagService service) {
        this.service = service;
    }

    /**
     * Returns true when the calling tenant has the flag enabled;
     * throws {@link FeatureNotEnabledException} when not. Throws on
     * miss (rather than returning false) so the SpEL @PreAuthorize
     * call surfaces the structured FEATURE_NOT_ENABLED envelope
     * instead of a plain AccessDeniedException.
     */
    public boolean requiresFlag(String featureKey) {
        UUID tenantId = TenantContext.require();
        if (!service.isEnabled(tenantId, featureKey)) {
            throw new FeatureNotEnabledException(featureKey);
        }
        return true;
    }
}
