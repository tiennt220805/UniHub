package com.unihub.workshop.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The raster formats a cover image may have (Phase 8, D122): JPEG, PNG and WebP, and nothing else.
 * Not SVG: an SVG served from the application's own origin can carry script, and the bucket's
 * response does not carry the CSP of the Web's nginx. A format is known by three things that must
 * agree: the {@code Content-Type} the upload URL was signed for, the extension of the issued key,
 * and the first bytes of the object ({@link #matches}); a client's word for the type is never
 * enough.
 */
public enum CoverImageFormat {
    JPEG("image/jpeg", "jpg") {
        @Override
        public boolean matches(byte[] head) {
            return startsWith(head, 0xFF, 0xD8, 0xFF);
        }
    },
    PNG("image/png", "png") {
        @Override
        public boolean matches(byte[] head) {
            return startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
        }
    },
    WEBP("image/webp", "webp") {
        @Override
        public boolean matches(byte[] head) {
            // RIFF <4 bytes of size> WEBP
            return startsWith(head, 'R', 'I', 'F', 'F')
                    && head.length >= SIGNATURE_BYTES
                    && head[8] == 'W'
                    && head[9] == 'E'
                    && head[10] == 'B'
                    && head[11] == 'P';
        }
    };

    /** The most the longest signature needs. */
    public static final int SIGNATURE_BYTES = 12;

    /** 2 MiB (Phase 8 D122): a 16:9 photograph at card size is a fraction of that. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private final String contentType;
    private final String extension;

    CoverImageFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** Whether {@code head}, the first bytes of an object, is this format. */
    public abstract boolean matches(byte[] head);

    public static Optional<CoverImageFormat> ofContentType(String contentType) {
        return Arrays.stream(values()).filter(f -> f.contentType.equals(contentType)).findFirst();
    }

    /** The format an issued key was made for: {@code covers/{workshop}/{uuid}.{extension}}. */
    public static Optional<CoverImageFormat> ofKey(String key) {
        return Arrays.stream(values())
                .filter(f -> key != null && key.endsWith("." + f.extension))
                .findFirst();
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head == null || head.length < signature.length) {
            return false;
        }
        for (var i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
