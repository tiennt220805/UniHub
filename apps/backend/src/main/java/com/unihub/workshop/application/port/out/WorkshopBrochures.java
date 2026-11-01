package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * The brochure columns of {@code workshops} ({@code pending_brochure_key}, {@code brochure_s3_key},
 * {@code ai_summary}), written by plain statements that leave {@code version} alone. They are not
 * seat data and not part of an organizer's edit: bumping the version would make a student who is
 * claiming the last seat at that moment lose the race for no reason (registration.md §2), and the
 * JPA entity does not write these columns, so an organizer's edit cannot overwrite them either.
 */
public interface WorkshopBrochures {

    /** The workshop's current {@code brochure_s3_key}; empty when it has none or does not exist. */
    Optional<String> currentKey(WorkshopId workshopId);

    /**
     * Stores the summary only if {@code brochureKey} is still the workshop's current key: a summary
     * of a brochure that has since been replaced must not land on the new one.
     *
     * @return whether the summary was stored
     */
    boolean recordSummary(WorkshopId workshopId, String brochureKey, String summary);

    /**
     * Remembers the key of the upload URL just issued, replacing an earlier one (workshop.md §7,
     * database-schema.md §10.4). The object of a replaced key is an orphan, the same accepted waste
     * as a replaced brochure.
     */
    void issue(WorkshopId workshopId, String pendingKey);

    /** The key issued and not yet confirmed; empty when none. */
    Optional<String> pendingKey(WorkshopId workshopId);

    /**
     * Moves {@code pendingKey} into {@code brochure_s3_key} and clears it, in one statement that
     * only matches while the pending key is still that one. The caller writes the summarization
     * request in the same transaction only when this returns true: of two confirmations racing for
     * one key, exactly one gets true.
     */
    boolean confirm(WorkshopId workshopId, String pendingKey);
}
