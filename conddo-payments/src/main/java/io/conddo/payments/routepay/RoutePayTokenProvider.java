package io.conddo.payments.routepay;

import io.conddo.payments.common.RoutePayUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RoutePay OAuth2 client_credentials token, cached so we don't re-auth on
 * every API call. Token TTL is 60 min per RoutePay's docs; we refresh at 50
 * min of age to give ourselves a safety margin against clock skew.
 *
 * <p>Only instantiated when real credentials are configured — otherwise
 * {@link DormantRoutePayClient} is in play and this isn't on the bean graph.
 */
@Component
@ConditionalOnExpression("'${routepay.client-id:}' != ''")
public class RoutePayTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(RoutePayTokenProvider.class);
    /** Refresh ahead of the actual 60-min expiry to dodge clock skew + in-flight latency. */
    private static final Duration MAX_TOKEN_AGE = Duration.ofMinutes(50);

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public RoutePayTokenProvider(
            @Value("${routepay.auth-base-url}") String authBaseUrl,
            @Value("${routepay.client-id}") String clientId,
            @Value("${routepay.client-secret}") String clientSecret,
            RestClient.Builder restClientBuilder) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = restClientBuilder.baseUrl(authBaseUrl).build();
    }

    /** Returns a non-expired access token, refreshing if needed. */
    public synchronized String accessToken() {
        CachedToken current = cached.get();
        if (current != null && current.isFresh()) {
            return current.token();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials"
                            + "&client_id=" + clientId
                            + "&client_secret=" + clientSecret)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("access_token") == null) {
                throw new RoutePayUnavailableException("RoutePay returned no access_token");
            }
            String token = response.get("access_token").toString();
            cached.set(new CachedToken(token, Instant.now()));
            return token;
        } catch (RuntimeException ex) {
            log.error("RoutePay token endpoint failed: {}", ex.getMessage());
            throw new RoutePayUnavailableException(
                    "Could not obtain RoutePay access token: " + ex.getMessage(), ex);
        }
    }

    private record CachedToken(String token, Instant fetchedAt) {
        boolean isFresh() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(MAX_TOKEN_AGE) < 0;
        }
    }
}
