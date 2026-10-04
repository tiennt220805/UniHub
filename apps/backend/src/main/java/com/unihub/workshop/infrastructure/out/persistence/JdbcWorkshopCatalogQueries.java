package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Catalog visibility rule from packages/api-contract/paths/workshop.yaml (listWorkshops): only
 * PUBLISHED rows are ever returned, regardless of caller. Free-text/speaker/location/date filtering
 * (M-05, Mục 2.4) is out of scope for this slice — criteria beyond page/size are accepted but not
 * yet applied.
 */
@Repository
class JdbcWorkshopCatalogQueries implements WorkshopCatalogQueries {
    private final JdbcTemplate jdbcTemplate;

    JdbcWorkshopCatalogQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkshopCatalogPage search(SearchWorkshopsCriteria criteria) {
        int page = criteria.page();
        int size = criteria.size();
        int offset = page * size;

        Long totalElements =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM workshops WHERE status = 'PUBLISHED'", Long.class);

        List<WorkshopCatalogEntry> items =
                jdbcTemplate.query(
                        "SELECT id, title, location, start_time, status FROM workshops "
                                + "WHERE status = 'PUBLISHED' ORDER BY start_time LIMIT ? OFFSET ?",
                        (rs, rowNum) ->
                                new WorkshopCatalogEntry(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("title"),
                                        rs.getString("location"),
                                        rs.getTimestamp("start_time").toInstant(),
                                        rs.getString("status")),
                        size,
                        offset);

        int totalPages = size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return new WorkshopCatalogPage(items, page, size, totalElements, totalPages);
    }
}
