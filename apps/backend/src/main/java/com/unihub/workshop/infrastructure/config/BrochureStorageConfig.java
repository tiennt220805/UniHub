package com.unihub.workshop.infrastructure.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 client and presigner (workshop.md §4). Built the same way everywhere; {@code endpoint} is
 * the only difference between MinIO and AWS. Building them contacts nothing, so the application
 * starts with no bucket reachable. One attempt per call, short timeouts: the callers are an HTTP
 * request (confirm) and a consumer with its own retry chain, and a bucket that hangs must cost
 * seconds, not the SDK's default minutes of retries.
 */
@Configuration
@EnableConfigurationProperties({ObjectStorageProperties.class, AiSummarizationProperties.class})
public class BrochureStorageConfig {
    private static final Logger LOG = LoggerFactory.getLogger(BrochureStorageConfig.class);

    @Bean(destroyMethod = "close")
    public S3Client brochureS3Client(ObjectStorageProperties settings) {
        var builder =
                S3Client.builder()
                        .region(Region.of(settings.region()))
                        .credentialsProvider(credentials(settings))
                        .serviceConfiguration(configuration(settings))
                        .httpClientBuilder(
                                ApacheHttpClient.builder()
                                        .connectionTimeout(settings.connectTimeout())
                                        .socketTimeout(settings.readTimeout()))
                        .overrideConfiguration(
                                ClientOverrideConfiguration.builder()
                                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                                        .apiCallAttemptTimeout(
                                                settings.connectTimeout()
                                                        .plus(settings.readTimeout()))
                                        .apiCallTimeout(
                                                settings.connectTimeout()
                                                        .plus(settings.readTimeout())
                                                        .multipliedBy(2))
                                        .build());
        if (settings.endpoint() != null) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner brochureS3Presigner(ObjectStorageProperties settings) {
        var builder =
                S3Presigner.builder()
                        .region(Region.of(settings.region()))
                        .credentialsProvider(credentials(settings))
                        .serviceConfiguration(configuration(settings));
        if (settings.endpoint() != null) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        return builder.build();
    }

    /**
     * Local runs and tests only ({@code unihub.object-storage.create-bucket}): makes the bucket if
     * it is missing. A deployed environment never sets it (the bucket is infrastructure). A bucket
     * that cannot be reached at start-up is logged, not fatal.
     */
    @Bean
    ApplicationRunner ensureBrochureBucket(S3Client client, ObjectStorageProperties settings) {
        return args -> {
            if (!settings.createBucket()) {
                return;
            }
            try {
                try {
                    client.headBucket(b -> b.bucket(settings.bucket()));
                } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException e) {
                    client.createBucket(b -> b.bucket(settings.bucket()));
                    LOG.info("created the bucket {}", settings.bucket());
                }
            } catch (RuntimeException e) {
                LOG.warn(
                        "could not check or create the bucket {}: {}",
                        settings.bucket(),
                        e.toString());
            }
        };
    }

    private static AwsCredentialsProvider credentials(ObjectStorageProperties settings) {
        return settings.accessKey() == null
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey()));
    }

    /** MinIO needs path-style addressing; AWS S3 keeps its default (workshop.md §4). */
    private static S3Configuration configuration(ObjectStorageProperties settings) {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(settings.endpoint() != null)
                .build();
    }
}
