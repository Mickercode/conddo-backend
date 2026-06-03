package io.conddo.studio.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import io.conddo.studio.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ClaudeClient} backed by the official Anthropic Java SDK. Built once from
 * {@code studio.ai.claude.*}; a blank API key leaves it unconfigured (every call
 * returns empty). 30-second timeout per the §20 AI rules; all failures are logged
 * and swallowed so a Claude outage never fails a Studio request.
 */
@Component
public class AnthropicClaudeClient implements ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClaudeClient.class);

    private final AnthropicClient client;   // null when no API key is configured
    private final String model;

    public AnthropicClaudeClient(StudioProperties properties) {
        StudioProperties.Ai.Claude claude = properties.ai() == null ? null : properties.ai().claude();
        String apiKey = claude == null ? null : claude.apiKey();
        this.model = claude != null && claude.model() != null && !claude.model().isBlank()
                ? claude.model().trim() : "claude-sonnet-4-6";
        this.client = (apiKey == null || apiKey.isBlank()) ? null
                : AnthropicOkHttpClient.builder()
                        .apiKey(apiKey.trim())
                        .timeout(Duration.ofSeconds(30))
                        .build();
        if (this.client == null) {
            log.info("Claude AI assistant is dormant (no CLAUDE_API_KEY set)");
        }
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public Optional<String> complete(String systemPrompt, String userPrompt, int maxTokens, boolean think) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens((long) maxTokens)
                    .system(systemPrompt)
                    .addUserMessage(userPrompt);
            if (think) {
                params.thinking(ThinkingConfigAdaptive.builder().build());
            }
            Message response = client.messages().create(params.build());
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (RuntimeException ex) {
            // §20 AI rule: log, never fail the request.
            log.error("Claude completion failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> completeWithImage(String systemPrompt, String userPrompt,
                                              String imageUrl, int maxTokens) {
        if (client == null) {
            return Optional.empty();
        }
        try {
            ContentBlockParam imageBlock = ContentBlockParam.ofImage(
                    ImageBlockParam.builder().urlSource(imageUrl).build());
            ContentBlockParam textBlock = ContentBlockParam.ofText(
                    TextBlockParam.builder().text(userPrompt).build());
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens((long) maxTokens)
                    .system(systemPrompt)
                    .addUserMessageOfBlockParams(List.of(imageBlock, textBlock))
                    .build();
            Message response = client.messages().create(params);
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining());
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (RuntimeException ex) {
            log.error("Claude vision call failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
