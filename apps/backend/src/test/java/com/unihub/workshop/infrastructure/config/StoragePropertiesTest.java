package com.unihub.workshop.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoragePropertiesTest {

    private static ObjectStorageProperties storage(
            String endpoint, String access, String secret, Duration connect) {
        return new ObjectStorageProperties(
                endpoint, null, null, null, access, secret, null, connect, null);
    }

    @Test
    @DisplayName(
            "nothing set: AWS endpoint resolution, the one bucket of workshop.md §5, short timeouts")
    void defaults() {
        var settings = storage(null, null, null, null);

        assertThat(settings.endpoint()).isNull();
        assertThat(settings.bucket()).isEqualTo("unihub-brochures");
        assertThat(settings.region()).isEqualTo("us-east-1");
        assertThat(settings.createBucket()).isFalse();
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a blank endpoint (an unset environment variable) means AWS, not an empty URL")
    void blankEndpoint() {
        assertThat(storage("  ", null, null, null).endpoint()).isNull();
        assertThat(storage(" http://minio:9000 ", null, null, null).endpoint())
                .isEqualTo("http://minio:9000");
    }

    @Test
    @DisplayName(
            "the browser's address of the bucket is the server's own until it is told otherwise")
    void publicEndpointDefaultsToTheEndpoint() {
        assertThat(storage("http://minio:9000", null, null, null).publicEndpoint())
                .isEqualTo("http://minio:9000");
        assertThat(storage(null, null, null, null).publicEndpoint()).isNull();

        var behindTheEdge =
                new ObjectStorageProperties(
                        "http://minio:9000",
                        " https://unihub.local ",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertThat(behindTheEdge.endpoint()).isEqualTo("http://minio:9000");
        assertThat(behindTheEdge.publicEndpoint()).isEqualTo("https://unihub.local");
        assertThat(behindTheEdge.toString()).contains("publicEndpoint=https://unihub.local");
    }

    @Test
    @DisplayName("the access key and the secret are set together or not at all")
    void credentialsComeInPairs() {
        assertThatThrownBy(() -> storage(null, "key", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(null, null, "secret", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage(null, "key", "secret", null).accessKey()).isEqualTo("key");
    }

    @Test
    @DisplayName("a timeout of zero or less is refused")
    void timeoutsArePositive() {
        assertThatThrownBy(() -> storage(null, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage(null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the credentials never appear in toString (INV-18)")
    void secretsAreNotPrinted() {
        var text = storage("http://minio", "AKIAEXAMPLE", "s3cr3t-value", null).toString();

        assertThat(text).doesNotContain("AKIAEXAMPLE").doesNotContain("s3cr3t-value");
    }

    @Test
    @DisplayName(
            "AI settings: 15 s by default (workshop.md §9), blank values are unset, the key is never printed")
    void aiSettings() {
        var unset = new AiSummarizationProperties(" ", "", null);
        assertThat(unset.baseUrl()).isNull();
        assertThat(unset.apiKey()).isNull();
        assertThat(unset.timeout()).isEqualTo(Duration.ofSeconds(15));

        var set = new AiSummarizationProperties("https://ai.example/", "sk-very-secret", null);
        assertThat(set.baseUrl()).isEqualTo("https://ai.example");
        assertThat(set.toString()).doesNotContain("sk-very-secret");

        assertThatThrownBy(() -> new AiSummarizationProperties("https://ai", "k", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "the anonymous read policy allows GetObject on covers/* of this bucket and nothing else")
    void coverReadPolicyIsNarrow() {
        var policy = BrochureStorageConfig.coverReadPolicy("unihub-brochures");

        assertThat(policy)
                .contains("\"Principal\":\"*\"")
                .contains("\"Action\":[\"s3:GetObject\"]")
                .contains("\"Resource\":[\"arn:aws:s3:::unihub-brochures/covers/*\"]")
                .doesNotContain("ListBucket")
                .doesNotContain("s3:*")
                .doesNotContain("PutObject")
                .doesNotContain("DeleteObject");
    }
}
