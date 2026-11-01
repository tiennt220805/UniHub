package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.BrochureStorage;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A map-backed {@link BrochureStorage} for use case tests; counts the calls that reach the bucket.
 */
final class InMemoryBrochureStorage implements BrochureStorage {
    final Map<String, Long> objects = new HashMap<>();
    int headCalls;
    RuntimeException headFailure;
    RuntimeException presignFailure;
    private final Clock clock;

    InMemoryBrochureStorage(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String newKey(WorkshopId workshopId) {
        return workshopId.value() + "/" + UUID.randomUUID() + ".pdf";
    }

    @Override
    public PresignedUpload presignUpload(String key, Duration ttl) {
        if (presignFailure != null) {
            throw presignFailure;
        }
        return new PresignedUpload(
                URI.create("https://bucket.test/unihub-brochures/" + key + "?X-Amz-Signature=x"),
                clock.instant().plus(ttl));
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        headCalls++;
        if (headFailure != null) {
            throw headFailure;
        }
        return Optional.ofNullable(objects.get(key)).map(ObjectInfo::new);
    }

    @Override
    public StoredObject open(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException();
    }

    static StorageUnavailableException down() {
        return new StorageUnavailableException("down", new IOException("connection refused"));
    }
}
