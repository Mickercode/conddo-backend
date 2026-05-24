package io.conddo.core.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies the platform's access tokens: short-lived JWTs signed
 * with RSA-256 (private key signs, public key verifies — PRD §6.2 / §12.1).
 *
 * <p>Each token carries the minimum a request needs to be authorised and
 * tenant-scoped without a database round-trip:
 * <ul>
 *   <li>{@code sub} — the user id</li>
 *   <li>{@code tenant_id} — the tenant the user belongs to (drives RLS)</li>
 *   <li>{@code role} — one of SUPER_ADMIN / TENANT_ADMIN / STAFF / CUSTOMER</li>
 * </ul>
 *
 * <p>This is a plain class (no Spring annotations) so it stays decoupled from
 * configuration and is trivially unit-testable; {@code conddo-api} constructs it
 * as a bean from the configured RSA key pair. Refresh tokens are deliberately
 * <em>not</em> JWTs — they are opaque, server-side, and revocable
 * ({@link RefreshTokenService}).
 */
public class JwtService {

    /** JWT claim carrying the tenant id; consumed when resolving the tenant per request. */
    public static final String CLAIM_TENANT_ID = "tenant_id";
    /** JWT claim carrying the user's role. */
    public static final String CLAIM_ROLE = "role";
    /** JWT claim carrying the tenant's vertical (Architecture §4.4). */
    public static final String CLAIM_VERTICAL = "vertical";
    /** JWT claim carrying the tenant's plan tier (Architecture §4.4). */
    public static final String CLAIM_PLAN = "plan";
    /** JWT claim carrying the tenant's active tool ids — drives the frontend manifest/gate (§4.4, §10). */
    public static final String CLAIM_ACTIVE_MODULES = "activeModules";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(RSAPublicKey publicKey, RSAPrivateKey privateKey, String issuer, Duration accessTokenTtl) {
        this(publicKey, privateKey, issuer, accessTokenTtl, Clock.systemUTC());
    }

    /** Package-friendly constructor allowing a fixed {@link Clock} in tests. */
    public JwtService(RSAPublicKey publicKey, RSAPrivateKey privateKey, String issuer,
                      Duration accessTokenTtl, Clock clock) {
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(jwk));
        this.encoder = new NimbusJwtEncoder(jwks);
        this.decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
        this.clock = clock;
    }

    /**
     * Mints a signed access token for the given principal. Expiry is
     * {@code accessTokenTtl} from now (15 minutes in production).
     */
    public String issueAccessToken(UUID userId, UUID tenantId, String role) {
        return issueAccessToken(userId, tenantId, role, null, null, List.of());
    }

    /**
     * Mints an access token carrying the tenant context the frontend needs to
     * drive manifest navigation and module gating (Architecture §4.4): the
     * tenant's {@code vertical}, {@code plan}, and {@code activeModules} (the
     * resolved tool ids), alongside {@code sub}/{@code tenant_id}/{@code role}.
     */
    public String issueAccessToken(UUID userId, UUID tenantId, String role,
                                   String vertical, String plan, List<String> activeModules) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .subject(userId.toString())
                .claim(CLAIM_TENANT_ID, tenantId.toString())
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_ACTIVE_MODULES, activeModules == null ? List.of() : activeModules);
        if (vertical != null) {
            claims.claim(CLAIM_VERTICAL, vertical);
        }
        if (plan != null) {
            claims.claim(CLAIM_PLAN, plan);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    /**
     * Mints an access token for an internal staff member (PRD v1.3 §22). Same
     * signing and {@code role} claim as tenant tokens, but carries <b>no</b>
     * {@code tenant_id} — staff are not tenant-scoped; SUPER_ADMIN selects a
     * tenant per request via the act-as header instead.
     */
    public String issueStaffAccessToken(UUID staffId, String internalRole) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .subject(staffId.toString())
                .claim(CLAIM_ROLE, internalRole)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Verifies signature + expiry and returns the decoded token. Throws
     * {@link org.springframework.security.oauth2.jwt.JwtException} if invalid or
     * expired. The resource server uses an equivalent {@link JwtDecoder} in the
     * filter chain; this is exposed for direct verification and tests.
     */
    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    /** The decoder backing this service, reused by the resource-server filter chain. */
    public JwtDecoder decoder() {
        return decoder;
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }
}
