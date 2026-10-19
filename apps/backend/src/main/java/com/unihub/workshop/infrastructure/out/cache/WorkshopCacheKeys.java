package com.unihub.workshop.infrastructure.out.cache;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Redis key formats of the workshop catalog (distributed-coordination.md §5, workshop.md §2). The
 * only place that spells a {@code unihub:cache:workshop:*} key, so no other module can touch one.
 */
final class WorkshopCacheKeys {

    private static final String PREFIX = "unihub:cache:workshop:";

    /** 128 bits of the SHA-256: ample for a 30s cache, and half the key length. */
    private static final int FILTER_HASH_HEX_LENGTH = 32;

    private WorkshopCacheKeys() {}

    static String detail(WorkshopId id) {
        return PREFIX + "detail:" + id.value();
    }

    static String seats(WorkshopId id) {
        return PREFIX + "seats:" + id.value();
    }

    static String list(SearchWorkshopsCriteria criteria) {
        return PREFIX + "list:" + filterHash(criteria);
    }

    /**
     * Deterministic for the same normalised query. Every value is length-prefixed, so two different
     * queries can never collapse into the same text (for instance {@code q="a|b"} versus {@code
     * q="a", speaker="b"}), and an absent value differs from an empty one.
     */
    static String filterHash(SearchWorkshopsCriteria c) {
        var canonical = new StringBuilder();
        for (var field :
                List.of(
                        text(c.q()),
                        text(c.speaker()),
                        text(c.location()),
                        text(c.dateFrom()),
                        text(c.dateTo()),
                        text(c.page()),
                        text(c.size()))) {
            canonical.append(field);
        }
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FILTER_HASH_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "~;";
        }
        var asText = value.toString();
        return asText.length() + ":" + asText + ";";
    }
}
