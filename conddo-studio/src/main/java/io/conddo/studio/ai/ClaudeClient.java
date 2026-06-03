package io.conddo.studio.ai;

import java.util.Optional;

/**
 * Port for the Claude (Anthropic) text-completion call used by the AI assistant
 * (§8). Implemented with the official Anthropic Java SDK; mockable in tests.
 *
 * <p>Per the §20 AI rules, this <b>never throws</b>: a failed call, a timeout, or
 * an unconfigured key all return {@link Optional#empty()} so the assistant can
 * degrade to "no suggestions" instead of failing the request.
 */
public interface ClaudeClient {

    /**
     * One non-streaming completion. Returns the concatenated text, or empty on any
     * failure / when not configured.
     *
     * @param think enable adaptive thinking (for genuinely complex tasks like the QA scan)
     */
    Optional<String> complete(String systemPrompt, String userPrompt, int maxTokens, boolean think);

    /**
     * Vision-enabled completion: pass an {@code imageUrl} and a text prompt — Claude
     * sees the image and answers. Same fail-safe contract as {@link #complete}.
     *
     * <p>Default falls back to a text-only call with the URL appended so test stubs
     * keep working without overriding. Production adapter overrides to use the
     * SDK's image content block (genuine vision).
     */
    default Optional<String> completeWithImage(String systemPrompt, String userPrompt,
                                               String imageUrl, int maxTokens) {
        return complete(systemPrompt, userPrompt + "\n\nImage URL: " + imageUrl, maxTokens, false);
    }

    /** Whether a Claude API key is configured (the assistant is live). */
    boolean isConfigured();
}
