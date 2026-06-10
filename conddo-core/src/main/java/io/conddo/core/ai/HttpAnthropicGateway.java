package io.conddo.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;

/**
 * Real Anthropic Messages API client. Active only when
 * {@code CONDDO_ANTHROPIC_API_KEY} is set, replacing
 * {@link DormantAnthropicGateway} as {@code @Primary}.
 *
 * <p>The model defaults to {@code claude-sonnet-4-6} (latest Sonnet at
 * the time of this slice — vision-capable, fast, cheap-enough for the
 * pharmacist workflow). Override via {@code conddo.anthropic.model}
 * if a different tier is preferred per-deploy.
 */
@Component
@Primary
@ConditionalOnExpression("'${conddo.anthropic.api-key:}' != ''")
public class HttpAnthropicGateway implements AnthropicGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAnthropicGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public HttpAnthropicGateway(
            @Value("${conddo.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${conddo.anthropic.api-key:}") String apiKey,
            @Value("${conddo.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${conddo.anthropic.max-tokens:1024}") int maxTokens,
            RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        log.info("AnthropicGateway active (model={}, maxTokens={})", model, maxTokens);
    }

    @Override
    public String chatWithImage(String imageUrl, String prompt) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "source", Map.of("type", "url", "url", imageUrl));
        Map<String, Object> textBlock = Map.of("type", "text", "text", prompt);
        return callMessages(List.of(imageBlock, textBlock));
    }

    @Override
    public String chatText(String prompt) {
        Map<String, Object> textBlock = Map.of("type", "text", "text", prompt);
        return callMessages(List.of(textBlock));
    }

    private String callMessages(List<Map<String, Object>> content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        try {
            JsonNode response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new AnthropicUnavailableException("Anthropic returned an empty body");
            }
            JsonNode contentArr = response.path("content");
            if (!contentArr.isArray() || contentArr.isEmpty()) {
                throw new AnthropicUnavailableException("Anthropic response missing content");
            }
            for (JsonNode block : contentArr) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText();
                }
            }
            throw new AnthropicUnavailableException("Anthropic response had no text block");
        } catch (AnthropicUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Anthropic call failed: {}", ex.toString());
            throw new AnthropicUnavailableException("Anthropic call failed: " + ex.getMessage(), ex);
        }
    }
}
