package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.WorkshopBrochures;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The brochure columns, in plain statements (see {@link WorkshopBrochures}): {@code version} is not
 * touched, and the JPA entity maps both columns read-only, so no other write can overwrite them.
 */
@Repository
class JdbcWorkshopBrochures implements WorkshopBrochures {
    private final JdbcTemplate jdbc;

    JdbcWorkshopBrochures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> currentKey(WorkshopId workshopId) {
        return jdbc
                .query(
                        "SELECT brochure_s3_key FROM workshops WHERE id = ?",
                        (rs, row) -> rs.getString(1),
                        workshopId.value())
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    @Override
    public boolean recordSummary(WorkshopId workshopId, String brochureKey, String summary) {
        return jdbc.update(
                        "UPDATE workshops SET ai_summary = ?, updated_at = now() "
                                + "WHERE id = ? AND brochure_s3_key = ?",
                        summary,
                        workshopId.value(),
                        brochureKey)
                == 1;
    }
}
