package io.conddo.core.features;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pharmacy Roadmap — canonical mapping of every {@code feature_key}
 * to its current lifecycle stage (live / beta / coming_soon). The
 * tenant_feature_flags table denormalises {@code status} per row for
 * historical accuracy; this catalogue is the source of truth for
 * defaults on a freshly-inserted row and for the "all features" list
 * the dashboard renders even when a tenant has no rows yet.
 *
 * <p>Moving a feature from {@code coming_soon} → {@code beta} or from
 * {@code beta} → {@code live} is a code edit here plus an
 * {@code UPDATE tenant_feature_flags SET status = ...} migration —
 * lets the FE keep using a single wire shape across lifecycle stages.
 */
public final class FeatureCatalogue {

    public enum Status {
        LIVE("live"),
        BETA("beta"),
        COMING_SOON("coming_soon");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Status fromWire(String wire) {
            if (wire == null) {
                return COMING_SOON;
            }
            for (Status s : values()) {
                if (s.wire.equals(wire)) {
                    return s;
                }
            }
            return COMING_SOON;
        }
    }

    /** Insertion-ordered so the FE renders them in the canonical order. */
    private static final Map<String, Status> CATALOGUE = new LinkedHashMap<>();

    static {
        // Beta — accessible on a per-tenant grant from the Conddo team.
        CATALOGUE.put("cashback_loyalty", Status.BETA);
        CATALOGUE.put("followup_workflow", Status.BETA);
        CATALOGUE.put("drug_programs", Status.BETA);
        CATALOGUE.put("emr_basic", Status.BETA);
        CATALOGUE.put("pos", Status.BETA);
        // Coming Soon — interest-list only; not yet built.
        CATALOGUE.put("multi_store", Status.COMING_SOON);
        CATALOGUE.put("offline_mobile", Status.COMING_SOON);
        CATALOGUE.put("customer_retainer", Status.COMING_SOON);
        CATALOGUE.put("barcode_scan", Status.COMING_SOON);
        CATALOGUE.put("emr_full", Status.COMING_SOON);
    }

    private FeatureCatalogue() {
    }

    public static Map<String, Status> all() {
        return CATALOGUE;
    }

    public static Status statusOf(String featureKey) {
        return CATALOGUE.getOrDefault(featureKey, Status.COMING_SOON);
    }

    public static boolean isKnown(String featureKey) {
        return CATALOGUE.containsKey(featureKey);
    }
}
