package com.unihub.workshop.infrastructure.in.messaging;

import com.unihub.shared.messaging.NonRetryableMessageException;
import com.unihub.workshop.application.command.SummarizeBrochureCommand;
import com.unihub.workshop.application.port.in.SummarizeBrochureUseCase;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Workshop's own summarization task (workshop.md §3, messaging.md §2). The listener maps and
 * delegates; it never catches (backend-coding-standards.md §8.5-§8.6), so a bucket or an AI service
 * that cannot be reached keeps the message in its retry chain (messaging.md §4).
 */
@Component
class SummarizationRequestedListener {
    static final String QUEUE = "unihub.summarization.queue";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** The fields of {@code unihub.workshop.summarization.requested} this module reads. */
    record SummarizationRequestedMessage(
            UUID workshopId, String brochureKey, Integer schemaVersion) {}

    private final SummarizeBrochureUseCase summarizeBrochure;

    SummarizationRequestedListener(SummarizeBrochureUseCase summarizeBrochure) {
        this.summarizeBrochure = summarizeBrochure;
    }

    @RabbitListener(queues = QUEUE)
    void onSummarizationRequested(SummarizationRequestedMessage message) {
        if (message.schemaVersion() == null
                || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new NonRetryableMessageException(
                    "unsupported schemaVersion " + message.schemaVersion());
        }
        if (message.workshopId() == null
                || message.brochureKey() == null
                || message.brochureKey().isBlank()) {
            throw new NonRetryableMessageException(
                    "summarization.requested without a workshop id or a brochure key");
        }
        summarizeBrochure.summarize(
                new SummarizeBrochureCommand(message.workshopId(), message.brochureKey()));
    }
}
