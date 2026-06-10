package io.conddo.api.pharmacy;

import io.conddo.core.domain.PharmacyReminder;
import io.conddo.core.service.PharmacyReminderService;
import io.conddo.core.service.PharmacyReminderService.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hourly walk of every {@code SCHEDULED} pharmacy reminder past its
 * {@code scheduled_at} (Pharmacy Spec v2 §12D). Defers each row to
 * {@link PharmacyReminderService#send} which runs in its own
 * {@code REQUIRES_NEW} transaction so one customer's send failure
 * never poisons another's.
 *
 * <p>Schedule is configurable for tests; the default
 * {@code 0 0 * * * *} fires at minute 0 of every hour. Tests pin
 * {@code conddo.pharmacy.reminder-cron} to a far-future expression
 * and call {@link #runOnce()} directly.
 */
@Component
public class PharmacyReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(PharmacyReminderScheduler.class);

    private final PharmacyReminderService service;

    public PharmacyReminderScheduler(PharmacyReminderService service) {
        this.service = service;
    }

    @Scheduled(cron = "${conddo.pharmacy.reminder-cron:0 0 * * * *}", zone = "UTC")
    public void runOnce() {
        try {
            List<PharmacyReminder> due = service.findDue();
            if (due.isEmpty()) {
                return;
            }
            log.info("Pharmacy reminder scan: {} due", due.size());
            int sent = 0, failed = 0;
            for (PharmacyReminder reminder : due) {
                try {
                    ProcessResult result = service.send(reminder.getId());
                    if (result.sent()) {
                        sent++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("Reminder {} send raised: {}", reminder.getId(), ex.getMessage());
                }
            }
            log.info("Pharmacy reminder scan complete: {} sent, {} failed", sent, failed);
        } catch (RuntimeException ex) {
            log.error("Pharmacy reminder scan failed: {}", ex.getMessage());
        }
    }
}
