package io.conddo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Conddo.io application entry point. Lives in the {@code io.conddo} root
 * package so component, entity and repository scanning covers both this module
 * and {@code conddo-core} ({@code io.conddo.core.*}).
 *
 * <p>{@code @EnableAsync} powers the {@code @Async} signup → Studio job
 * hand-off in {@link io.conddo.api.signup.TenantActivationListener} so the
 * user's signup response never waits on a sleeping Studio service.
 *
 * <p>{@code @EnableScheduling} powers the hourly subscription-expiry scan
 * ({@link io.conddo.api.billing.BillingExpiryScheduler}) that walks every
 * live subscription past its {@code expires_at} and fires the
 * trialing/active → grace → expired transitions + merchant notifications.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ConddoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConddoApplication.class, args);
    }
}
