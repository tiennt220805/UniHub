package com.unihub.workshop.application.port.out;

import java.io.InputStream;

/**
 * The AI Summarization Service (workshop.md §9): one synchronous call, no retry inside it (the
 * consumer's own retry chain is the retry). Phase 5 runs against a stub only; nothing here names a
 * provider.
 */
public interface SummarizationClient {

    /**
     * @param pdf the verified brochure, streamed (not buffered a second time)
     * @param contentLength its size in bytes
     * @throws SummarizationUnavailableException for every outcome that may succeed later: a
     *     timeout, a connection failure, a {@code 5xx}, any {@code 4xx} other than {@code 422}, an
     *     unreadable answer, a service that is not configured
     */
    SummaryOutcome summarize(InputStream pdf, long contentLength);

    sealed interface SummaryOutcome {
        /**
         * {@code 200}: plain text, at most 500 characters (the client truncates, workshop.md §9).
         */
        record Summary(String text) implements SummaryOutcome {}

        /**
         * {@code 422 UNSUPPORTED_CONTENT}, or an empty summary: terminal for this brochure, so
         * nothing is stored and the message is acknowledged, never retried.
         */
        record Unsupported() implements SummaryOutcome {}
    }

    class SummarizationUnavailableException extends RuntimeException {
        public SummarizationUnavailableException(String message) {
            super(message);
        }

        public SummarizationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
