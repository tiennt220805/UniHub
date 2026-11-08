package com.unihub.workshop.application.port.out;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Optional;

/**
 * The cover image columns of {@code workshops} ({@code pending_cover_image_key}, {@code
 * cover_image_key}), written by plain statements that leave {@code version} alone, for the reason
 * {@link WorkshopBrochures} gives: a cover is neither seat data nor part of an Organizer's edit,
 * and the JPA entity does not write these columns.
 */
public interface WorkshopCoverImages {

    /** Remembers the key of the upload URL just issued, replacing an earlier one. */
    void issue(WorkshopId workshopId, String pendingKey);

    /** The key issued and not yet confirmed or rejected; empty when none. */
    Optional<String> pendingKey(WorkshopId workshopId);

    /**
     * Moves {@code pendingKey} into {@code cover_image_key} and clears it, in one statement that
     * only matches while the pending key is still that one. Of two confirmations racing for one
     * key, exactly one gets true.
     */
    boolean confirm(WorkshopId workshopId, String pendingKey);

    /**
     * Forgets {@code pendingKey} (a rejected object); a no-op when it is no longer the pending one.
     */
    void discard(WorkshopId workshopId, String pendingKey);
}
