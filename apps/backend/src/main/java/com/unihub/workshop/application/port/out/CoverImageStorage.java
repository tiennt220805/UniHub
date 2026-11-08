package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.BrochureStorage.PresignedUpload;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.domain.model.CoverImageFormat;
import java.time.Duration;
import java.util.Optional;

/**
 * The cover images in the bucket (workshop.md §4, Phase 8, D120-D122): the same bucket as the
 * brochures, under {@code covers/}. The only code that touches them. The upload goes from the
 * browser to the bucket on a presigned URL; the object is read back here only to be checked (its
 * size and type from {@code HeadObject}, its first bytes by a ranged read), and is served to
 * browsers by the edge, never by the Backend API.
 *
 * <p>Every method except {@link #newKey} and {@link #presignUpload} calls the bucket and throws
 * {@link StorageUnavailableException} when it cannot be reached or refuses the request.
 */
public interface CoverImageStorage {

    /** {@code covers/{workshop_id}/{uuid}.{ext}}: random, immutable, never a client's name. */
    String newKey(WorkshopId workshopId, CoverImageFormat format);

    /**
     * A URL that authorizes exactly one {@code PUT} of {@code contentLength} bytes of {@code
     * format} to {@code key}, valid for {@code ttl}. Computed locally: it still works while the
     * bucket is down.
     */
    PresignedUpload presignUpload(
            String key, CoverImageFormat format, long contentLength, Duration ttl);

    /** The object's size and stored {@code Content-Type}, or empty when nothing is at the key. */
    Optional<StoredCover> head(String key);

    /** At most the first {@code count} bytes of the object ({@code Range: bytes=0-count-1}). */
    byte[] readStart(String key, int count);

    /** Removes the object; absent is not an error. */
    void delete(String key);

    record StoredCover(long contentLength, String contentType) {}
}
