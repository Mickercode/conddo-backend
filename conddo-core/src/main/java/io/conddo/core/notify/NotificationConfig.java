package io.conddo.core.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables {@link NotificationProperties} for the notification channels. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {
}
