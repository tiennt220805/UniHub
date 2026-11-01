package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SummarizeBrochureCommand;
import com.unihub.workshop.application.port.out.BrochureStorage;
import com.unihub.workshop.application.port.out.BrochureStorage.ObjectInfo;
import com.unihub.workshop.application.port.out.BrochureStorage.ObjectNotFoundException;
import com.unihub.workshop.application.port.out.BrochureStorage.PresignedUpload;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.BrochureStorage.StoredObject;
import com.unihub.workshop.application.port.out.SummarizationClient;
import com.unihub.workshop.application.port.out.SummarizationClient.SummarizationUnavailableException;
import com.unihub.workshop.application.port.out.SummarizationClient.SummaryOutcome;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The summarization consumer's rules on fakes (workshop.md §3, §6, §8, §9; Phase 5 checklist 9.3,
 * M-50): what is checked on the object, in which order, and what ends in a quiet acknowledgement
 * rather than a retry.
 */
class SummarizeBrochureTest {
    private static final WorkshopId WORKSHOP = WorkshopId.newId();
    private static final String KEY = WORKSHOP.value() + "/6f1c.pdf";
    private static final long TEN_MB = 10L * 1024 * 1024;

    private final FakeBucket bucket = new FakeBucket();
    private final FakeBrochures brochures = new FakeBrochures();
    private final FakeAi ai = new FakeAi();
    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final SummarizeBrochure useCase = new SummarizeBrochure(brochures, bucket, ai, cache);

    private static byte[] pdf(int size) {
        var bytes = new byte[size];
        var head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
        return bytes;
    }

    private void confirmed(byte[] content) {
        brochures.keys.put(WORKSHOP, KEY);
        bucket.objects.put(KEY, content);
    }

    private void run() {
        useCase.summarize(new SummarizeBrochureCommand(WORKSHOP.value(), KEY));
    }

    @Test
    @DisplayName(
            "a PDF is sent to the AI service whole, and its summary is stored and the cache dropped")
    void summarizes() {
        var content = pdf(2_000);
        confirmed(content);
        ai.answer = new SummaryOutcome.Summary("Một buổi workshop về khóa phân tán.");

        run();

        assertThat(ai.received).containsExactly(content);
        assertThat(brochures.summaries)
                .containsEntry(WORKSHOP, "Một buổi workshop về khóa phân tán.");
        assertThat(bucket.deleted).isEmpty();
    }

    @Test
    @DisplayName("workshop.md §9: a summary longer than 500 characters is cut to 500")
    void truncates() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Summary("a".repeat(501));

        run();

        assertThat(brochures.summaries.get(WORKSHOP)).hasSize(500);
    }

    @Test
    @DisplayName("a 500-character summary is kept as it is, and the cut never splits a character")
    void truncationBoundary() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Summary("b".repeat(500));
        run();
        assertThat(brochures.summaries.get(WORKSHOP)).hasSize(500);

        // 499 letters then an emoji (two UTF-16 units) then more: the cut falls after the emoji.
        var text = "c".repeat(499) + "😀" + "tail";
        assertThat(SummarizeBrochure.truncate(text)).isEqualTo("c".repeat(499) + "😀");
    }

    @Test
    @DisplayName("an object over 10 MB is deleted and never read or sent (size is checked first)")
    void tooLarge() {
        confirmed(pdf(1_000));
        bucket.claimedLength = TEN_MB + 1;

        run();

        assertThat(bucket.deleted).containsExactly(KEY);
        assertThat(bucket.bodyRead).as("not a byte of the body was read").isFalse();
        assertThat(ai.calls).isZero();
        assertThat(brochures.summaries).isEmpty();
    }

    @Test
    @DisplayName("exactly 10 MB is accepted (the limit is inclusive)")
    void exactlyTenMegabytes() {
        confirmed(pdf(1_000));
        bucket.claimedLength = TEN_MB;
        ai.answer = new SummaryOutcome.Summary("ok");

        run();

        assertThat(ai.calls).isEqualTo(1);
        assertThat(bucket.deleted).isEmpty();
    }

    @Test
    @DisplayName(
            "an object that does not start with %PDF- is deleted and the AI service is not called")
    void notAPdf() {
        confirmed("MZ\u0090\u0000 an executable".getBytes(StandardCharsets.ISO_8859_1));

        run();

        assertThat(bucket.deleted).containsExactly(KEY);
        assertThat(ai.calls).isZero();
        assertThat(brochures.summaries).isEmpty();
        assertThat(brochures.keys)
                .as("brochure_s3_key is left as it was")
                .containsEntry(WORKSHOP, KEY);
    }

    @Test
    @DisplayName(
            "§6: the check is on the bytes, not the name: a wrong type with a .pdf key is rejected")
    void nameIsNotTrusted() {
        confirmed("<html>not a pdf</html>".getBytes(StandardCharsets.US_ASCII));

        run();

        assertThat(bucket.deleted).containsExactly(KEY);
    }

    @Test
    @DisplayName("an empty object and one shorter than the magic number are rejected")
    void tooShortToBeAPdf() {
        confirmed(new byte[0]);
        run();
        assertThat(bucket.deleted).containsExactly(KEY);

        bucket.deleted.clear();
        confirmed("%PDF".getBytes(StandardCharsets.US_ASCII));
        run();
        assertThat(bucket.deleted).containsExactly(KEY);
        assertThat(ai.calls).isZero();
    }

    @Test
    @DisplayName("exactly %PDF- and nothing more is a PDF as far as the type check goes")
    void magicOnly() {
        confirmed("%PDF-".getBytes(StandardCharsets.US_ASCII));
        ai.answer = new SummaryOutcome.Unsupported();

        run();

        assertThat(ai.calls).isEqualTo(1);
        assertThat(bucket.deleted).isEmpty();
    }

    @Test
    @DisplayName("the object is released before it is deleted")
    void closedBeforeDelete() {
        confirmed("not a pdf".getBytes(StandardCharsets.US_ASCII));

        run();

        assertThat(bucket.events).containsExactly("open", "close", "delete");
    }

    @Test
    @DisplayName("§9: a 422 from the AI service stores nothing and finishes the message")
    void unsupported() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Unsupported();

        run();

        assertThat(ai.calls).isEqualTo(1);
        assertThat(brochures.summaries).isEmpty();
        assertThat(bucket.deleted).as("an unsupported PDF stays in the bucket").isEmpty();
    }

    @Test
    @DisplayName("an AI service that cannot be used makes the message retry, and stores nothing")
    void aiUnavailable() {
        confirmed(pdf(100));
        ai.failure = new SummarizationUnavailableException("503");

        assertThatThrownBy(this::run).isInstanceOf(SummarizationUnavailableException.class);
        assertThat(brochures.summaries).isEmpty();
        assertThat(bucket.deleted).isEmpty();
    }

    @Test
    @DisplayName(
            "a bucket that cannot be used makes the message retry, and the AI service is not called")
    void bucketUnavailable() {
        confirmed(pdf(100));
        bucket.failure = new StorageUnavailableException("down", new IOException());

        assertThatThrownBy(this::run).isInstanceOf(StorageUnavailableException.class);
        assertThat(ai.calls).isZero();
    }

    @Test
    @DisplayName("a request for a brochure that has been replaced is finished without any call")
    void staleKey() {
        brochures.keys.put(WORKSHOP, WORKSHOP.value() + "/newer.pdf");
        bucket.objects.put(KEY, pdf(100));

        run();

        assertThat(bucket.events).isEmpty();
        assertThat(ai.calls).isZero();
        assertThat(brochures.summaries).isEmpty();
    }

    @Test
    @DisplayName(
            "a workshop with no brochure, and one that does not exist, are finished without any call")
    void noBrochure() {
        run();

        assertThat(bucket.events).isEmpty();
        assertThat(ai.calls).isZero();
    }

    @Test
    @DisplayName(
            "an object that is gone (a rejection already removed it) is finished without an AI call")
    void objectGone() {
        brochures.keys.put(WORKSHOP, KEY);

        run();

        assertThat(ai.calls).isZero();
        assertThat(bucket.deleted).isEmpty();
    }

    @Test
    @DisplayName(
            "a summary that lands after the brochure was replaced is not stored, and drops no cache")
    void replacedWhileSummarizing() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Summary("late");
        ai.beforeAnswer = () -> brochures.keys.put(WORKSHOP, WORKSHOP.value() + "/newer.pdf");

        run();

        assertThat(brochures.summaries).isEmpty();
        assertThat(cache.invalidated).isEmpty();
    }

    @Test
    @DisplayName("§2: the detail cache entry is dropped only once a summary is stored")
    void cacheInvalidation() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Summary("done");

        run();
        assertThat(cache.invalidated).hasSize(1);

        ai.answer = new SummaryOutcome.Unsupported();
        run();
        assertThat(cache.invalidated).as("unsupported changes nothing").hasSize(1);
    }

    @Test
    @DisplayName("a redelivery of the current brochure is a call to the AI service each time (C32)")
    void redeliveryCallsAgain() {
        confirmed(pdf(100));
        ai.answer = new SummaryOutcome.Summary("same");

        run();
        run();
        run();

        assertThat(ai.calls).isEqualTo(3);
        assertThat(brochures.summaries).containsEntry(WORKSHOP, "same");
    }

    // ---------- fakes ----------

    private static final class FakeBucket implements BrochureStorage {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Long claimedLength;
        boolean bodyRead;
        RuntimeException failure;

        @Override
        public String newKey(WorkshopId workshopId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedUpload presignUpload(String key, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ObjectInfo> head(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StoredObject open(String key) {
            if (failure != null) {
                throw failure;
            }
            var content = objects.get(key);
            if (content == null) {
                throw new ObjectNotFoundException(key);
            }
            events.add("open");
            var length = claimedLength != null ? claimedLength : content.length;
            return new StoredObject() {
                @Override
                public long contentLength() {
                    return length;
                }

                @Override
                public InputStream stream() {
                    return new ByteArrayInputStream(content) {
                        @Override
                        public synchronized int read(byte[] b, int off, int len) {
                            bodyRead = true;
                            return super.read(b, off, len);
                        }

                        @Override
                        public synchronized int read() {
                            bodyRead = true;
                            return super.read();
                        }
                    };
                }

                @Override
                public void close() {
                    events.add("close");
                }
            };
        }

        @Override
        public void delete(String key) {
            events.add("delete");
            deleted.add(key);
            objects.remove(key);
        }
    }

    private static final class FakeBrochures implements WorkshopBrochures {
        final Map<WorkshopId, String> keys = new HashMap<>();
        final Map<WorkshopId, String> summaries = new HashMap<>();

        @Override
        public Optional<String> currentKey(WorkshopId workshopId) {
            return Optional.ofNullable(keys.get(workshopId));
        }

        @Override
        public boolean recordSummary(WorkshopId workshopId, String brochureKey, String summary) {
            if (!brochureKey.equals(keys.get(workshopId))) {
                return false;
            }
            summaries.put(workshopId, summary);
            return true;
        }
    }

    private static final class FakeAi implements SummarizationClient {
        final List<byte[]> received = new ArrayList<>();
        int calls;
        SummaryOutcome answer = new SummaryOutcome.Summary("summary");
        RuntimeException failure;
        Runnable beforeAnswer = () -> {};

        @Override
        public SummaryOutcome summarize(InputStream pdf, long contentLength) {
            calls++;
            try {
                received.add(pdf.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            if (failure != null) {
                throw failure;
            }
            beforeAnswer.run();
            return answer;
        }
    }
}
