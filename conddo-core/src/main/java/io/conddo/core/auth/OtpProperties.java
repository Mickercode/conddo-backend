package io.conddo.core.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for signup phone-verification OTPs (bound from
 * {@code conddo.security.otp.*}). A 4-digit code is low entropy (10k combos),
 * so the attempt/expiry/resend limits here are the real defence.
 *
 * @param codeLength     number of digits in the code (4, matching the UI)
 * @param ttl            how long a code stays valid (e.g. 10m)
 * @param maxAttempts    failed verify attempts before the code is dead (e.g. 5)
 * @param resendCooldown minimum wait between resends (e.g. 30s)
 * @param maxResends     total codes a single registration may request (e.g. 5)
 */
@ConfigurationProperties(prefix = "conddo.security.otp")
public record OtpProperties(
        int codeLength,
        Duration ttl,
        int maxAttempts,
        Duration resendCooldown,
        int maxResends
) {
}
