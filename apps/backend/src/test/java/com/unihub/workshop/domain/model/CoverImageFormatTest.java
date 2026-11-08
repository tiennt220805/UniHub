package com.unihub.workshop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoverImageFormatTest {

    private static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static final byte[] JPEG =
            bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1);
    private static final byte[] PNG =
            bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13);
    private static final byte[] WEBP =
            "RIFF$\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] GIF =
            "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000"
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("each format recognises its own first bytes and no other format's")
    void eachFormatMatchesOnlyItself() {
        assertThat(CoverImageFormat.JPEG.matches(JPEG)).isTrue();
        assertThat(CoverImageFormat.PNG.matches(PNG)).isTrue();
        assertThat(CoverImageFormat.WEBP.matches(WEBP)).isTrue();

        for (var format : CoverImageFormat.values()) {
            for (var other : new byte[][] {JPEG, PNG, WEBP}) {
                if (format.matches(other)) {
                    continue;
                }
                assertThat(format.matches(other)).isFalse();
            }
        }
        assertThat(CoverImageFormat.JPEG.matches(PNG)).isFalse();
        assertThat(CoverImageFormat.PNG.matches(WEBP)).isFalse();
        assertThat(CoverImageFormat.WEBP.matches(JPEG)).isFalse();
    }

    @Test
    @DisplayName("a GIF, an SVG, an empty or a truncated file matches no format")
    void rejectsEverythingElse() {
        for (var format : CoverImageFormat.values()) {
            assertThat(format.matches(GIF)).isFalse();
            assertThat(format.matches(SVG)).isFalse();
            assertThat(format.matches(new byte[0])).isFalse();
            assertThat(format.matches(null)).isFalse();
            assertThat(format.matches(bytes(0xFF, 0xD8))).isFalse();
        }
    }

    @Test
    @DisplayName("a RIFF container that is not WebP, or is too short to say, is not WebP")
    void webpNeedsTheWebpTag() {
        var wave = "RIFF$\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(CoverImageFormat.WEBP.matches(wave)).isFalse();
        assertThat(
                        CoverImageFormat.WEBP.matches(
                                "RIFF$\u0000\u0000\u0000WEB".getBytes(StandardCharsets.ISO_8859_1)))
                .isFalse();
    }

    @Test
    @DisplayName("the content type, the key's extension and the format agree, and SVG is nowhere")
    void lookups() {
        assertThat(CoverImageFormat.ofContentType("image/jpeg")).contains(CoverImageFormat.JPEG);
        assertThat(CoverImageFormat.ofContentType("image/png")).contains(CoverImageFormat.PNG);
        assertThat(CoverImageFormat.ofContentType("image/webp")).contains(CoverImageFormat.WEBP);
        assertThat(CoverImageFormat.ofContentType("image/svg+xml")).isEmpty();
        assertThat(CoverImageFormat.ofContentType("image/gif")).isEmpty();
        assertThat(CoverImageFormat.ofContentType(null)).isEmpty();

        assertThat(CoverImageFormat.ofKey("covers/w/abc.jpg")).contains(CoverImageFormat.JPEG);
        assertThat(CoverImageFormat.ofKey("covers/w/abc.png")).contains(CoverImageFormat.PNG);
        assertThat(CoverImageFormat.ofKey("covers/w/abc.webp")).contains(CoverImageFormat.WEBP);
        assertThat(CoverImageFormat.ofKey("covers/w/abc.svg")).isEmpty();
        assertThat(CoverImageFormat.ofKey(null)).isEmpty();
    }

    @Test
    @DisplayName("the limit is 2 MiB and the longest signature fits in 12 bytes")
    void limits() {
        assertThat(CoverImageFormat.MAX_BYTES).isEqualTo(2_097_152L);
        assertThat(CoverImageFormat.SIGNATURE_BYTES).isEqualTo(12);
    }
}
