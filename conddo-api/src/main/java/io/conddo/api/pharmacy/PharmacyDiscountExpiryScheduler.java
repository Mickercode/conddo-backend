package io.conddo.api.pharmacy;

import io.conddo.core.service.PharmacyDiscountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep that flips APPROVED pharmacy discounts past their
 * {@code ends_at} to {@code EXPIRED} (Pharmacy Spec v2 §12B
 * implementation note "auto-expires discounts at endsAt"). Runs
 * cross-tenant via the V38 RLS carve-out.
 *
 * <p>Schedule is configurable; tests pin
 * {@code conddo.pharmacy.discount-expiry-cron} to a far-future
 * expression and call {@link #runOnce()} directly.
 */
@Component
public class PharmacyDiscountExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PharmacyDiscountExpiryScheduler.class);

    private final PharmacyDiscountService discountService;

    public PharmacyDiscountExpiryScheduler(PharmacyDiscountService discountService) {
        this.discountService = discountService;
    }

    @Scheduled(cron = "${conddo.pharmacy.discount-expiry-cron:0 0 * * * *}", zone = "UTC")
    public void runOnce() {
        try {
            int swept = discountService.sweepExpired();
            if (swept > 0) {
                log.info("Pharmacy discount expiry sweep: {} flipped to EXPIRED", swept);
            }
        } catch (RuntimeException ex) {
            log.error("Pharmacy discount expiry sweep failed: {}", ex.getMessage());
        }
    }
}
