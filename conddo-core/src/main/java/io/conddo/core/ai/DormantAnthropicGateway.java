package io.conddo.core.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in {@link AnthropicGateway} used when no API key is configured.
 * Every call throws {@link AnthropicNotConfiguredException} so the
 * service can map it to a clean 503; lets the rest of the BE boot
 * fine on environments without the key (local dev, tests).
 */
@Component
public class DormantAnthropicGateway implements AnthropicGateway {

    private static final Logger log = LoggerFactory.getLogger(DormantAnthropicGateway.class);

    public DormantAnthropicGateway() {
        log.info("AnthropicGateway is dormant — set CONDDO_ANTHROPIC_API_KEY to enable the AI Product Assistant");
    }

    @Override
    public String chatWithImage(String imageUrl, String prompt) {
        throw new AnthropicNotConfiguredException();
    }

    @Override
    public String chatText(String prompt) {
        throw new AnthropicNotConfiguredException();
    }
}
