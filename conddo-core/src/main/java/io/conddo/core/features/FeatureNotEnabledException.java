package io.conddo.core.features;

/**
 * Thrown by the Beta feature flag guard when the calling tenant
 * doesn't have {@code enabled = true} on the row for this feature.
 * GlobalExceptionHandler maps this to 403 {@code FEATURE_NOT_ENABLED}
 * with a {@code featureKey} field hint (HANDOFF_2026-06-11 §0).
 */
public class FeatureNotEnabledException extends RuntimeException {

    private final String featureKey;

    public FeatureNotEnabledException(String featureKey) {
        super("This feature is not yet enabled on your account.");
        this.featureKey = featureKey;
    }

    public String getFeatureKey() {
        return featureKey;
    }
}
