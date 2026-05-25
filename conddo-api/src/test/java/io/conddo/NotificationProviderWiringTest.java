package io.conddo;

import io.conddo.core.notify.EmailSender;
import io.conddo.core.notify.LoggingEmailSender;
import io.conddo.core.notify.LoggingSmsSender;
import io.conddo.core.notify.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the deploy that crashed with "No qualifying bean of type
 * SmsSender". The logging stubs are unconditional defaults, so a blank or unknown
 * {@code conddo.notifications.*.provider} degrades to logging instead of leaving
 * the context with zero senders. Fast — no web server or database.
 */
class NotificationProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingSmsSender.class, LoggingEmailSender.class);

    @Test
    void blankProviderStillResolvesToALoggingSender() {
        runner.withPropertyValues(
                        "conddo.notifications.sms.provider=",
                        "conddo.notifications.email.provider=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(SmsSender.class).isInstanceOf(LoggingSmsSender.class);
                    assertThat(ctx).getBean(EmailSender.class).isInstanceOf(LoggingEmailSender.class);
                });
    }

    @Test
    void unknownProviderStillHasASender() {
        runner.withPropertyValues(
                        "conddo.notifications.sms.provider=carrier-pigeon",
                        "conddo.notifications.email.provider=smoke-signal")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(SmsSender.class);
                    assertThat(ctx).hasSingleBean(EmailSender.class);
                });
    }

    @Test
    void absentProviderHasLoggingSenders() {
        runner.run(ctx -> {
            assertThat(ctx).getBean(SmsSender.class).isInstanceOf(LoggingSmsSender.class);
            assertThat(ctx).getBean(EmailSender.class).isInstanceOf(LoggingEmailSender.class);
        });
    }
}
