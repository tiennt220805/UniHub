package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SummarizeBrochureCommand;
import com.unihub.workshop.application.port.in.SummarizeBrochureUseCase;
import com.unihub.workshop.application.port.out.BrochureStorage;
import com.unihub.workshop.application.port.out.BrochureStorage.ObjectNotFoundException;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.BrochureStorage.StoredObject;
import com.unihub.workshop.application.port.out.SummarizationClient;
import com.unihub.workshop.application.port.out.SummarizationClient.SummaryOutcome;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import com.unihub.workshop.application.port.out.WorkshopCache;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads a confirmed brochure, checks it on its real bytes, has the AI service summarize it, and
 * stores the result (workshop.md §3, §8, §9). Not transactional: the object-storage read and the
 * HTTP call must not hold a database transaction, and the one write is a single statement.
 *
 * <p>Every delivery of the same message is another call to a paid service, so it is cheap where it
 * can be: a request for a brochure that has been replaced, or whose object a rejection already
 * removed, is finished without a call. A redelivery of the current brochure is not skipped (the
 * last write wins, workshop.md §3), and the tests count the calls that makes.
 */
@Service
class SummarizeBrochure implements SummarizeBrochureUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(SummarizeBrochure.class);

    /** workshop.md §6: 10 MB, checked on the object's own {@code Content-Length}. */
    static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** workshop.md §9: what is accepted from the AI service, whatever it sends. */
    static final int MAX_SUMMARY_CHARACTERS = 500;

    private final WorkshopBrochures brochures;
    private final BrochureStorage storage;
    private final SummarizationClient summarizer;
    private final WorkshopCache cache;

    SummarizeBrochure(
            WorkshopBrochures brochures,
            BrochureStorage storage,
            SummarizationClient summarizer,
            WorkshopCache cache) {
        this.brochures = brochures;
        this.storage = storage;
        this.summarizer = summarizer;
        this.cache = cache;
    }

    @Override
    public void summarize(SummarizeBrochureCommand command) {
        var workshopId = WorkshopId.of(command.workshopId());
        var key = command.brochureKey();
        if (brochures.currentKey(workshopId).filter(key::equals).isEmpty()) {
            LOG.info("summarization skipped: {} is no longer a brochure of {}", key, workshopId);
            return;
        }

        SummaryOutcome outcome;
        String rejection;
        try (StoredObject object = storage.open(key)) {
            rejection = tooLarge(object);
            if (rejection == null) {
                var stream = new PushbackInputStream(object.stream(), PDF_MAGIC.length);
                rejection = notAPdf(stream);
                outcome =
                        rejection == null
                                ? summarizer.summarize(stream, object.contentLength())
                                : null;
            } else {
                outcome = null;
            }
        } catch (ObjectNotFoundException e) {
            LOG.info("summarization skipped: {} is gone from the bucket", key);
            return;
        }

        if (rejection != null) {
            // First, before anything else (workshop.md §8): a rejected file does not linger. The
            // stream is closed by now (the try block ended), which a delete needs.
            storage.delete(key);
            LOG.warn("brochure {} rejected and deleted: {}", key, rejection);
            return;
        }
        if (outcome instanceof SummaryOutcome.Summary summary) {
            var text = truncate(summary.text());
            if (brochures.recordSummary(workshopId, key, text)) {
                // Otherwise a student sees "summary pending" for up to 300 s (workshop.md §2, §3).
                cache.invalidate(workshopId);
            }
        } else {
            LOG.info("no summary for {}: the service cannot process this brochure", key);
        }
    }

    /** Size first, from the object's headers, before a byte of its body is read. */
    private static String tooLarge(StoredObject object) {
        return object.contentLength() > MAX_BYTES ? "larger than " + MAX_BYTES + " bytes" : null;
    }

    /**
     * The first bytes, never the name or a content type (workshop.md §6). Pushes them back, so the
     * stream is whole again for the AI service when this returns null.
     */
    private static String notAPdf(PushbackInputStream stream) {
        try {
            var head = stream.readNBytes(PDF_MAGIC.length);
            if (!Arrays.equals(head, PDF_MAGIC)) {
                return "does not start with %PDF-";
            }
            stream.unread(head);
            return null;
        } catch (IOException e) {
            throw new StorageUnavailableException("could not read the brochure", e);
        }
    }

    static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_SUMMARY_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_SUMMARY_CHARACTERS));
    }
}
