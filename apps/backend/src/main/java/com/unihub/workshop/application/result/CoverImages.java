package com.unihub.workshop.application.result;

/**
 * The public address of a workshop's cover image (workshop.md §4, Phase 8 D120/D121): a path on the
 * application's own origin, {@code /media/} followed by the object key. It is a pure function of
 * the key so no layer needs configuration to build it; the edge (nginx, the Istio gateway, the Vite
 * proxy) maps {@code /media/covers/*} onto the bucket for anonymous {@code GET}/{@code HEAD}, and
 * serves nothing else under {@code /media/}.
 */
public final class CoverImages {
    /** Where the edge serves covers from. Part of the contract of {@code coverImageUrl}. */
    public static final String URL_PREFIX = "/media/";

    private CoverImages() {}

    /** {@code null} for a workshop that has no cover (the Web draws its own, D123). */
    public static String urlOf(String objectKey) {
        return objectKey == null ? null : URL_PREFIX + objectKey;
    }
}
