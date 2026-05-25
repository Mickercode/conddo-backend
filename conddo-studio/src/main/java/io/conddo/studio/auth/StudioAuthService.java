package io.conddo.studio.auth;

import io.conddo.studio.common.InvalidCredentialsException;
import io.conddo.studio.config.StudioProperties;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.domain.StaffRefreshToken;
import io.conddo.studio.repository.StaffRefreshTokenRepository;
import io.conddo.studio.repository.StaffRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Authenticates Studio staff and issues HMAC access tokens + opaque refresh
 * tokens (Infrastructure §12). Refresh tokens are random, stored as a SHA-256
 * hash, and rotated on use. Internal tool — single shared studio.staff identity.
 */
@Service
public class StudioAuthService {

    private final StaffRepository staffRepository;
    private final StaffRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final StudioJwtService jwtService;
    private final StudioProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public StudioAuthService(StaffRepository staffRepository, StaffRefreshTokenRepository refreshTokens,
                             PasswordEncoder passwordEncoder, StudioJwtService jwtService,
                             StudioProperties properties) {
        this.staffRepository = staffRepository;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Transactional
    public AuthResult login(String email, String rawPassword) {
        Staff staff = staffRepository.findByEmail(email)
                .filter(Staff::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, staff.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        staff.recordLogin(OffsetDateTime.now(clock));
        staffRepository.save(staff);
        return issueFor(staff);
    }

    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        StaffRefreshToken token = refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(InvalidCredentialsException::new);
        Staff staff = staffRepository.findById(token.getStaffId())
                .filter(Staff::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        token.revoke(now);                 // rotation: the old token is single-use
        refreshTokens.save(token);
        return issueFor(staff);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(t -> {
                    t.revoke(OffsetDateTime.now(clock));
                    refreshTokens.save(t);
                });
    }

    private AuthResult issueFor(Staff staff) {
        String accessToken = jwtService.issueAccessToken(staff.getId(), staff.getRole(), staff.getFullName());
        String rawRefresh = randomToken();
        refreshTokens.save(new StaffRefreshToken(staff.getId(), sha256(rawRefresh),
                OffsetDateTime.now(clock).plus(properties.jwt().refreshTtl())));
        return new AuthResult(accessToken, jwtService.accessTtl().toSeconds(), rawRefresh, staff);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** The result of a successful login/refresh. */
    public record AuthResult(String accessToken, long expiresInSeconds, String refreshToken, Staff staff) {
    }
}
