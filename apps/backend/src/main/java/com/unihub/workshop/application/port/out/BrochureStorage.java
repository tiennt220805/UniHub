package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The brochure bucket (workshop.md §4-§8): S3, or MinIO behind the same client. The only code that
 * touches object storage; the Backend API never carries a brochure's bytes on the upload path (a
 * presigned PUT goes from the browser to the bucket), and the summarization consumer is the only
 * reader (no presigned GET exists).
 *
 * <p>Every method except {@link #newKey} and {@link #presignUpload} calls the bucket and throws
 * {@link StorageUnavailableException} when it cannot be reached or refuses the request.
 */
public interface BrochureStorage {

    /** {@code {workshop_id}/{uuid}.pdf} (workshop.md §5): random, never a client-supplied name. */
    String newKey(WorkshopId workshopId);

    /**
     * A URL that authorizes exactly one {@code PUT} of a PDF to {@code key}, valid for {@code ttl}.
     * Computed locally: it needs no round trip, so it still works while the bucket is down.
     */
    PresignedUpload presignUpload(String key, Duration ttl);

    /** The object's size, or empty when nothing exists at the key ({@code HeadObject}). */
    Optional<ObjectInfo> head(String key);

    /**
     * Opens the object for reading ({@code GetObject}). The caller closes it, and must close it
     * before deleting the key.
     *
     * @throws ObjectNotFoundException when nothing exists at the key
     */
    StoredObject open(String key);

    /** Removes the object; absent is not an error. */
    void delete(String key);

    record PresignedUpload(URI url, Instant expiresAt) {}

    record ObjectInfo(long contentLength) {}

    /** An open object: its size from the response headers, and its bytes as a stream. */
    interface StoredObject extends AutoCloseable {
        long contentLength();

        InputStream stream();

        @Override
        void close();
    }

    /** The bucket cannot be used right now (not reachable, timed out, or it refused us). */
    class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class ObjectNotFoundException extends RuntimeException {
        public ObjectNotFoundException(String key) {
            super("no object at " + key);
        }
    }
}
