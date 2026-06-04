package io.conddo.payments.routepay;

import com.fasterxml.jackson.databind.JsonNode;
import io.conddo.payments.common.RoutePayUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real RoutePay adapter. Active only when {@code routepay.client-id} is set,
 * replacing {@link DormantRoutePayClient} as {@code @Primary}.
 *
 * <p>Wraps the three calls we make: sub-account create (per tenant at signup),
 * SetRequest (hosted checkout init), and GetTransaction (status verify).
 * Transport failures surface as {@link RoutePayUnavailableException} so
 * controllers map them to a clean 502.
 */
@Component
@Primary
@ConditionalOnExpression("'${routepay.client-id:}' != ''")
public class HttpRoutePayClient implements RoutePayClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRoutePayClient.class);
    /** Connect / read timeouts — short enough that a stuck call doesn't queue up requests. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final RoutePayTokenProvider tokenProvider;

    public HttpRoutePayClient(
            @Value("${routepay.base-url}") String baseUrl,
            RestClient.Builder restClientBuilder,
            RoutePayTokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.tokenProvider = tokenProvider;
        log.info("RoutePay client active (base={})", baseUrl);
    }

    @Override
    public SubAccountResult createSubAccount(UUID tenantId, String businessName, String contactEmail) {
        // TODO(routepay-contract): verify body + response field names against the
        //   live sandbox once we exchange the first real call. Path is from
        //   developer.routepay.com (bills/api/v1/subaccounts); body field names
        //   are best-effort from the §7a spec.
        try {
            Map<String, Object> body = Map.of(
                    "externalReference", tenantId.toString(),
                    "businessName", businessName,
                    "contactEmail", contactEmail);
            JsonNode response = post("/bills/api/v1/subaccounts", body);
            String subaccountId = firstNonNull(textOrNull(response, "subAccountId"),
                    textOrNull(response, "subaccountId"),
                    textOrNull(response, "id"));
            if (subaccountId == null) {
                throw new RoutePayUnavailableException("RoutePay sub-account response missing subAccountId");
            }
            return new SubAccountResult(subaccountId);
        } catch (RoutePayUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RoutePayUnavailableException("RoutePay sub-account create failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public InitPaymentResult initPayment(InitPaymentRequest request) {
        // TODO(routepay-contract): verify request body field names + response keys
        //   against sandbox. Path /payment/api/v1/Payment/SetRequest is from the
        //   dev portal; amount is sent as kobo (integer minor units).
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reference", request.reference());
            body.put("amount", request.amountKobo());
            body.put("currency", "NGN");
            body.put("customerEmail", request.customerEmail());
            body.put("customerName", request.customerName());
            body.put("description", request.description() == null ? "" : request.description());
            body.put("returnUrl", request.returnUrl());
            body.put("subAccountId", request.tenantSubaccountId());
            body.put("platformFeeBps", request.platformFeeBps());

            JsonNode response = post("/payment/api/v1/Payment/SetRequest", body);
            String txnRef = firstNonNull(textOrNull(response, "transactionRef"),
                    textOrNull(response, "transactionReference"),
                    textOrNull(response, "reference"));
            String url = firstNonNull(textOrNull(response, "paymentUrl"),
                    textOrNull(response, "checkoutUrl"),
                    textOrNull(response, "redirectUrl"));
            if (txnRef == null || url == null) {
                throw new RoutePayUnavailableException(
                        "RoutePay SetRequest returned missing transactionRef or paymentUrl");
            }
            return new InitPaymentResult(txnRef, url);
        } catch (RoutePayUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RoutePayUnavailableException("RoutePay SetRequest failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public GetTransactionResult getTransaction(String routepayReference) {
        // Path is a path param per the dev portal (not a query string).
        try {
            JsonNode response = get("/payment/api/v1/Payment/GetTransaction/" + routepayReference);
            return new GetTransactionResult(
                    firstNonNull(textOrNull(response, "transactionRef"),
                            textOrNull(response, "transactionReference")),
                    textOrNull(response, "status"),
                    response.path("fee").isNumber() ? response.path("fee").asLong() : null,
                    textOrNull(response, "failureReason"));
        } catch (RuntimeException ex) {
            throw new RoutePayUnavailableException("RoutePay GetTransaction failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private JsonNode post(String path, Object body) {
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode get(String path) {
        return restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
                .retrieve()
                .body(JsonNode.class);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText(null);
    }

    /** Returns the first non-null value — used to tolerate RoutePay field-name variance. */
    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
