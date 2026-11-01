package com.unihub.workshop.infrastructure.out.client;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.BrochureStorage;
import com.unihub.workshop.infrastructure.config.ObjectStorageProperties;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The brochure bucket through the AWS SDK's {@code S3Client} (workshop.md §4): MinIO or S3, the
 * same calls. Every failure that is not "nothing there" becomes {@link
 * StorageUnavailableException}; the SDK's exception types go no further than this class.
 */
@Component
class S3BrochureStorage implements BrochureStorage {
    static final String CONTENT_TYPE = "application/pdf";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3BrochureStorage(S3Client client, S3Presigner presigner, ObjectStorageProperties settings) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = settings.bucket();
    }

    @Override
    public String newKey(WorkshopId workshopId) {
        return workshopId.value() + "/" + UUID.randomUUID() + ".pdf";
    }

    @Override
    public PresignedUpload presignUpload(String key, Duration ttl) {
        try {
            var presigned =
                    presigner.presignPutObject(
                            r ->
                                    r.signatureDuration(ttl)
                                            .putObjectRequest(
                                                    p ->
                                                            p.bucket(bucket)
                                                                    .key(key)
                                                                    .contentType(CONTENT_TYPE)));
            return new PresignedUpload(
                    java.net.URI.create(presigned.url().toString()), presigned.expiration());
        } catch (SdkException e) {
            throw new StorageUnavailableException("could not presign an upload", e);
        }
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            var head = client.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(head.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // HeadObject has no body, so a missing key arrives as a plain 404, not NoSuchKey.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageUnavailableException("HeadObject failed", e);
        } catch (SdkException e) {
            throw new StorageUnavailableException("HeadObject failed", e);
        }
    }

    @Override
    public StoredObject open(String key) {
        try {
            ResponseInputStream<GetObjectResponse> response =
                    client.getObject(r -> r.bucket(bucket).key(key));
            return new Opened(response);
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ObjectNotFoundException(key);
            }
            throw new StorageUnavailableException("GetObject failed", e);
        } catch (SdkException e) {
            throw new StorageUnavailableException("GetObject failed", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageUnavailableException("DeleteObject failed", e);
        }
    }

    private static final class Opened implements StoredObject {
        private final ResponseInputStream<GetObjectResponse> response;

        Opened(ResponseInputStream<GetObjectResponse> response) {
            this.response = response;
        }

        @Override
        public long contentLength() {
            return response.response().contentLength();
        }

        @Override
        public InputStream stream() {
            return response;
        }

        /** Aborts what was not read: a rejected object is not worth downloading to the end. */
        @Override
        public void close() {
            response.abort();
        }
    }
}
