package io.conddo.studio.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.conddo.studio.config.StudioProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and verifies the Studio's internal-staff access tokens: HMAC (HS256)
 * JWTs signed with {@code STUDIO_JWT_SECRET} — deliberately separate from the
 * platform's RSA tenant tokens (Infrastructure §12.1). Carries the staff id
 * ({@code sub}), {@code role}, and display {@code name}.
 */
@Service
public class StudioJwtService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_NAME = "name";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final Duration accessTtl;
    private final Clock clock = Clock.systemUTC();

    public StudioJwtService(StudioProperties properties) {
        StudioProperties.Jwt jwt = properties.jwt();
        SecretKey key = new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.issuer = jwt.issuer();
        this.accessTtl = jwt.accessTtl();
    }

    public String issueAccessToken(UUID staffId, String role, String fullName) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .subject(staffId.toString())
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_NAME, fullName)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
