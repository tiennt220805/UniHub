package com.unihub.workshop.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code unihub.object-storage.*} (workshop.md §4, §5). The same client in every environment; only
 * {@code endpoint} differs: blank means real AWS S3 (the SDK's own endpoint resolution), set means
 * MinIO or any other S3-compatible server, with path-style addressing. The credentials come from
 * the environment at run time, never from source or committed config (INV-18); with neither set the
 * SDK's default credentials chain is used. Nothing here is required at start-up: the brochure flow
 * is off the critical path, and an unreachable bucket is exactly the case it must survive.
 *
 * <p>{@code publicEndpoint} (Phase 8, D120) is the address a <em>browser</em> reaches the bucket
 * at, which is what a presigned URL must name: the signature covers the host and the path, so the
 * URL has to be minted for the origin the browser really sends the request to (the application's
 * own origin, where the edge forwards the bucket's path without touching the host or the path). It
 * defaults to {@code endpoint}, which is right whenever the browser and the server see the bucket
 * at the same address (tests, a bucket on the open Internet). The S3 client itself always uses
 * {@code endpoint}, the address from inside.
 */
@ConfigurationProperties(prefix = "unihub.object-storage")
public record ObjectStorageProperties(
        String endpoint,
        String publicEndpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        Boolean createBucket,
        Duration connectTimeout,
        Duration readTimeout) {

    public ObjectStorageProperties {
        endpoint = endpoint == null || endpoint.isBlank() ? null : endpoint.strip();
        publicEndpoint =
                publicEndpoint == null || publicEndpoint.isBlank()
                        ? endpoint
                        : publicEndpoint.strip();
        region = region == null || region.isBlank() ? "us-east-1" : region.strip();
        bucket = bucket == null || bucket.isBlank() ? "unihub-brochures" : bucket.strip();
        accessKey = accessKey == null || accessKey.isBlank() ? null : accessKey;
        secretKey = secretKey == null || secretKey.isBlank() ? null : secretKey;
        createBucket = createBucket != null && createBucket;
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(2), "connect-timeout");
        readTimeout = positive(readTimeout, Duration.ofSeconds(5), "read-timeout");
        if ((accessKey == null) != (secretKey == null)) {
            throw new IllegalArgumentException(
                    "unihub.object-storage.access-key and secret-key are set together or not at all");
        }
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        var result = value == null ? fallback : value;
        if (result.isNegative() || result.isZero()) {
            throw new IllegalArgumentException(
                    "unihub.object-storage." + name + " must be positive");
        }
        return result;
    }

    /** Never prints the credentials. */
    @Override
    public String toString() {
        return "ObjectStorageProperties[endpoint="
                + endpoint
                + ", publicEndpoint="
                + publicEndpoint
                + ", region="
                + region
                + ", bucket="
                + bucket
                + ", createBucket="
                + createBucket
                + "]";
    }
}
