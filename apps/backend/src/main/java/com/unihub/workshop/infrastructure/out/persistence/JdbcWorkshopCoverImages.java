package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopCoverImages;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The cover image columns, in plain statements (see {@link WorkshopCoverImages}): {@code version}
 * is not touched, and the JPA entity maps {@code cover_image_key} read-only, so no other write can
 * overwrite it.
 */
@Repository
class JdbcWorkshopCoverImages implements WorkshopCoverImages {
    private final JdbcTemplate jdbc;

    JdbcWorkshopCoverImages(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void issue(WorkshopId workshopId, String pendingKey) {
        jdbc.update(
                "UPDATE workshops SET pending_cover_image_key = ?, updated_at = now() WHERE id = ?",
                pendingKey,
                workshopId.value());
    }

    @Override
    public Optional<String> pendingKey(WorkshopId workshopId) {
        return jdbc
                .query(
                        "SELECT pending_cover_image_key FROM workshops WHERE id = ?",
                        (rs, row) -> rs.getString(1),
                        workshopId.value())
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    @Override
    public boolean confirm(WorkshopId workshopId, String pendingKey) {
        return jdbc.update(
                        "UPDATE workshops SET cover_image_key = ?, pending_cover_image_key = NULL, "
                                + "updated_at = now() WHERE id = ? AND pending_cover_image_key = ?",
                        pendingKey,
                        workshopId.value(),
                        pendingKey)
                == 1;
    }

    @Override
    public void discard(WorkshopId workshopId, String pendingKey) {
        jdbc.update(
                "UPDATE workshops SET pending_cover_image_key = NULL, updated_at = now() "
                        + "WHERE id = ? AND pending_cover_image_key = ?",
                workshopId.value(),
                pendingKey);
    }
}
