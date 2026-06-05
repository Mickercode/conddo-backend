package io.conddo.api.web.dto;

import io.conddo.core.service.BillingService;

import java.util.Map;

/**
 * Plan catalogue wire shape (BILLING_TIERS_SPEC §4). Prices are sent in
 * <b>Naira</b> integers (not Kobo) — converted in {@link #from} from the
 * DB's Kobo storage. The {@code features} map carries the {@code feature_key →
 * value} pairs verbatim (strings: {@code "true"}, {@code "false"}, integers,
 * or {@code "unlimited"}).
 */
public record PlanDto(
        String id,                       // canonical name — launcher / growth / scaler
        String displayName,
        Integer monthlyPrice,            // Naira
        Integer quarterlyPrice,          // Naira; null on Scaler
        boolean isCustom,
        Map<String, String> features) {

    public static PlanDto from(BillingService.PlanWithFeatures p) {
        return new PlanDto(
                p.plan().getName(),
                p.plan().getDisplayName(),
                koboToNaira(p.plan().getMonthlyPrice()),
                koboToNaira(p.plan().getQuarterlyPrice()),
                p.plan().isCustom(),
                p.features());
    }

    private static Integer koboToNaira(Integer kobo) {
        return kobo == null ? null : kobo / 100;
    }
}
