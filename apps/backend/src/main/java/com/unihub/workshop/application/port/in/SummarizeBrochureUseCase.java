package com.unihub.workshop.application.port.in;

import com.unihub.workshop.application.command.SummarizeBrochureCommand;

/** The summarization consumer's use case (workshop.md §3, §8, §9). */
public interface SummarizeBrochureUseCase {

    /**
     * Returns normally when the message is finished with: summarized, rejected (a file that is not
     * a PDF under 10 MB is deleted and never sent to the AI service), unsupported (the service
     * answered {@code 422}), or stale (the brochure was replaced, or is gone). Throws, so that the
     * message walks the retry chain, only when the bucket or the AI service cannot be used now.
     */
    void summarize(SummarizeBrochureCommand command);
}
