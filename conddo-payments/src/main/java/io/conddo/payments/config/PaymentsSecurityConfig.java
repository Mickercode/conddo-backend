package io.conddo.payments.config;

import io.conddo.payments.auth.PaymentsSecurityErrorResponder;
import io.conddo.payments.auth.PaymentsServiceTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Payments security: stateless. Three auth surfaces:
 * <ol>
 *   <li>{@code /api/payments/webhooks/**} — public, signature-verified.</li>
 *   <li>{@code /api/payments/internal/**} — service-token (X-Payments-Service-Token).</li>
 *   <li>Everything else — Bearer JWT (RSA-verified against conddo-api's public key).</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
public class PaymentsSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   PaymentsSecurityErrorResponder errorResponder,
                                                   PaymentsServiceTokenFilter serviceTokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Webhooks: signature-verified inside the controller, no JWT/token here.
                        .requestMatchers("/api/payments/webhooks/**").permitAll()
                        // Service-to-service intake — authenticated by PaymentsServiceTokenFilter.
                        .requestMatchers("/api/payments/internal/**").hasRole("SERVICE")
                        // Public health probe.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(errorResponder)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(serviceTokenFilter, BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder));
        return http.build();
    }

    /**
     * Loads the RSA public key conddo-api signs tenant JWTs with. If the key
     * file isn't present (dev / pre-config Render boots), we fall back to a
     * decoder that fails every token — keeping the service alive while every
     * inbound Bearer surfaces a clean 401. Tests override this bean via
     * {@code @Primary} and never hit this path.
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${payments.jwt.public-key}") Resource publicKeyResource) {
        try (InputStream stream = publicKeyResource.getInputStream()) {
            String pem = new String(stream.readAllBytes())
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(der));
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        } catch (Exception ex) {
            // Fail-closed: the bean exists but rejects every token, so the
            // service boots and the operator sees clean 401s instead of a
            // crashed JVM. Set CONDDO_JWT_PUBLIC_KEY to enable JWT auth.
            return token -> {
                throw new org.springframework.security.oauth2.jwt.JwtException(
                        "JWT verification is not configured on this service");
            };
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${payments.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins,
            @Value("${payments.cors.allowed-origin-patterns:}") List<String> allowedOriginPatterns) {
        CorsConfiguration config = new CorsConfiguration();
        if (!allowedOrigins.isEmpty()) {
            config.setAllowedOrigins(allowedOrigins);
        }
        if (allowedOriginPatterns != null && !allowedOriginPatterns.isEmpty()
                && !(allowedOriginPatterns.size() == 1 && allowedOriginPatterns.get(0).isBlank())) {
            config.setAllowedOriginPatterns(allowedOriginPatterns);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-RoutePay-Signature"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName("role");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
