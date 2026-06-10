package io.conddo.core.ai;

/**
 * Adapter for Anthropic's Messages API used by the AI Product
 * Assistant (Pharmacy Spec v2 §12C). Two real call shapes —
 * vision (an imageUrl plus a structured prompt) and pure text — both
 * return raw model output text the service parses as JSON.
 */
public interface AnthropicGateway {

    /**
     * Vision call. {@code imageUrl} is the public CDN-hosted image
     * uploaded by the pharmacist; {@code prompt} is the structured
     * "return JSON only" instruction that drives §12C product
     * extraction.
     */
    String chatWithImage(String imageUrl, String prompt);

    /** Pure text call — used by the description generation endpoint. */
    String chatText(String prompt);

    /** Thrown when Anthropic returns an error or the call times out. */
    class AnthropicUnavailableException extends RuntimeException {
        public AnthropicUnavailableException(String message) {
            super(message);
        }

        public AnthropicUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when the AI Product Assistant is not configured on this
     * deployment (no {@code CONDDO_ANTHROPIC_API_KEY}). The service
     * layer surfaces this as a 503 so the FE can render "AI assistant
     * is offline" instead of a generic 500.
     */
    class AnthropicNotConfiguredException extends RuntimeException {
        public AnthropicNotConfiguredException() {
            super("Anthropic AI is not configured on this deployment.");
        }
    }
}
