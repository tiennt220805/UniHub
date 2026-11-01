package com.unihub.workshop.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.ai-summarization.*} (workshop.md §9). Only configuration: nothing of the Phase 5
 * stub is hardcoded, so attaching a real provider later is a change of these values (the API key is
 * a run-time secret, {@code UNIHUB_AI_SUMMARIZATION_API_KEY}, never in source or committed config)
 * or of one adapter. Not required at start-up (workshop.md §9): without a {@code base-url} a
 * summary is simply never generated, and the consumer's message walks its retry chain.
 */
@ConfigurationProperties(prefix = "unihub.ai-summarization")
public record AiSummarizationProperties(String baseUrl, String apiKey, Duration timeout) {

    /** workshop.md §9: 15 s for connect and read, no retry inside the call. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public AiSummarizationProperties {
        baseUrl =
                baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip().replaceAll("/+$", "");
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("unihub.ai-summarization.timeout must be positive");
        }
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "AiSummarizationProperties[baseUrl=" + baseUrl + ", timeout=" + timeout + "]";
    }
}
