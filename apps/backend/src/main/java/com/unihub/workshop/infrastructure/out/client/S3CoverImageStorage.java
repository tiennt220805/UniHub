package com.unihub.workshop.infrastructure.out.client;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.out.observation.OutboundCall;
import com.unihub.workshop.application.port.out.BrochureStorage.PresignedUpload;
import com.unihub.workshop.application.port.out.BrochureStorage.StorageUnavailableException;
import com.unihub.workshop.application.port.out.CoverImageStorage;
import com.unihub.workshop.domain.model.CoverImageFormat;
import com.unihub.workshop.infrastructure.config.ObjectStorageProperties;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The cover images in the bucket through the AWS SDK (workshop.md §4, Phase 8): the same client and
 * presigner as {@link S3BrochureStorage}, keys under {@code covers/}. The upload URL is signed for
 * the {@code Content-Type} and the exact {@code Content-Length}, so what a signature-checking
 * bucket accepts is the declared file and nothing else. The SDK's exception types go no further
 * than this class.
 */
@Component
class S3CoverImageStorage implements CoverImageStorage {
    static final String PREFIX = "covers/";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final ObservationRegistry observations;

    S3CoverImageStorage(
            S3Client client,
            S3Presigner presigner,
            ObjectStorageProperties settings,
            ObservationRegistry observations) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = settings.bucket();
        this.observations = observations;
    }

    @Override
    public String newKey(WorkshopId workshopId, CoverImageFormat format) {
        return PREFIX + workshopId.value() + "/" + UUID.randomUUID() + "." + format.extension();
    }

    @Override
    public PresignedUpload presignUpload(
            String key, CoverImageFormat format, long contentLength, Duration ttl) {
        try {
            var presigned =
                    presigner.presignPutObject(
                            r ->
                                    r.signatureDuration(ttl)
                                            .putObjectRequest(
                                                    p ->
                                                            p.bucket(bucket)
                                                                    .key(key)
                                                                    .contentType(
                                                                            format.contentType())
                                                                    .contentLength(contentLength)));
            return new PresignedUpload(
                    URI.create(presigned.url().toString()), presigned.expiration());
        } catch (SdkException e) {
            throw new StorageUnavailableException("could not presign an upload", e);
        }
    }

    @Override
    public Optional<StoredCover> head(String key) {
        try {
            var head = call("head", () -> client.headObject(r -> r.bucket(bucket).key(key)));
            return Optional.of(new StoredCover(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageUnavailableException("HeadObject failed", e);
        } catch (SdkException e) {
            throw new StorageUnavailableException("HeadObject failed", e);
        }
    }

    @Override
    public byte[] readStart(String key, int count) {
        try {
            return call(
                    "get",
                    () -> {
                        try (var in =
                                client.getObject(
                                        r ->
                                                r.bucket(bucket)
                                                        .key(key)
                                                        .range("bytes=0-" + (count - 1)))) {
                            return in.readNBytes(count);
                        } catch (IOException e) {
                            throw new StorageUnavailableException("GetObject failed", e);
                        }
                    });
        } catch (S3Exception e) {
            throw new StorageUnavailableException("GetObject failed", e);
        } catch (SdkException e) {
            throw new StorageUnavailableException("GetObject failed", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            call("delete", () -> client.deleteObject(r -> r.bucket(bucket).key(key)));
        } catch (SdkException e) {
            throw new StorageUnavailableException("DeleteObject failed", e);
        }
    }

    private <T> T call(String operation, Supplier<T> request) {
        return OutboundCall.observe(
                observations,
                S3BrochureStorage.OBSERVATION,
                operation,
                request::get,
                answer -> OutboundCall.SUCCESS,
                failure ->
                        failure instanceof S3Exception s3 && s3.statusCode() == 404
                                ? S3BrochureStorage.NOT_FOUND
                                : OutboundCall.failureOutcome(failure));
    }
}
