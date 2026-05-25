package io.conddo.studio.config;

import io.conddo.studio.auth.StudioJwtService;
import io.conddo.studio.auth.StudioSecurityErrorResponder;
import io.conddo.studio.auth.StudioServiceTokenFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Studio security: stateless HMAC-JWT resource server. {@code /api/jobs/auth/**}
 * and health are public; everything else needs a valid STUDIO_JWT. The token's
 * {@code role} claim becomes a {@code ROLE_*} authority for method-level
 * {@code @PreAuthorize} (DEVELOPER / QA_REVIEWER / TEAM_LEAD / ADMIN).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(StudioProperties.class)
public class StudioSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder studioJwtDecoder,
                                                   StudioSecurityErrorResponder errorResponder,
                                                   StudioServiceTokenFilter serviceTokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/jobs/auth/login", "/api/jobs/auth/refresh",
                                "/api/jobs/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Service-to-service intake — authenticated by the X-Studio-Service-Token filter.
                        .requestMatchers("/api/jobs/intake/**").hasRole("SERVICE")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(errorResponder)
                        .jwt(jwt -> jwt.decoder(studioJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(serviceTokenFilter, BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder));
        return http.build();
    }

    @Bean
    public StudioServiceTokenFilter studioServiceTokenFilter(StudioProperties properties) {
        return new StudioServiceTokenFilter(properties.service() == null ? null : properties.service().token());
    }

    @Bean
    public JwtDecoder studioJwtDecoder(StudioJwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(StudioProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Maps the {@code role} claim to a single {@code ROLE_<role>} authority. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName(StudioJwtService.CLAIM_ROLE);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
