package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import com.unihub.workshop.application.port.out.WorkshopCatalogQueries;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Catalog visibility rule from packages/api-contract/paths/workshop.yaml (listWorkshops): only
 * PUBLISHED rows are ever returned, regardless of caller. Filters (M-05) are ANDed: {@code q}
 * matches title or description, {@code speaker}/{@code location} their column, all as
 * case-insensitive substrings with LIKE wildcards in user input escaped; {@code dateFrom}/{@code
 * dateTo} bound {@code start_time} inclusively. Values are always bound parameters.
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

        var where = new StringBuilder("WHERE status = 'PUBLISHED'");
        var args = new ArrayList<Object>();
        if (criteria.q() != null) {
            where.append(
                    " AND (title ILIKE ? ESCAPE '\\' OR COALESCE(description, '') ILIKE ? ESCAPE '\\')");
            args.add(contains(criteria.q()));
            args.add(contains(criteria.q()));
        }
        if (criteria.speaker() != null) {
            where.append(" AND speaker_name ILIKE ? ESCAPE '\\'");
            args.add(contains(criteria.speaker()));
        }
        if (criteria.location() != null) {
            where.append(" AND location ILIKE ? ESCAPE '\\'");
            args.add(contains(criteria.location()));
        }
        if (criteria.dateFrom() != null) {
            where.append(" AND start_time >= ?");
            args.add(Timestamp.from(criteria.dateFrom()));
        }
        if (criteria.dateTo() != null) {
            where.append(" AND start_time <= ?");
            args.add(Timestamp.from(criteria.dateTo()));
        }

        Long totalElements =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM workshops " + where, Long.class, args.toArray());

        var pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<WorkshopCatalogEntry> items =
                jdbcTemplate.query(
                        "SELECT id, title, location, start_time, status FROM workshops "
                                + where
                                + " ORDER BY start_time, id LIMIT ? OFFSET ?",
                        (rs, rowNum) ->
                                new WorkshopCatalogEntry(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("title"),
                                        rs.getString("location"),
                                        rs.getTimestamp("start_time").toInstant(),
                                        rs.getString("status")),
                        pageArgs.toArray());

        int totalPages = size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return new WorkshopCatalogPage(items, page, size, totalElements, totalPages);
    }

    private static String contains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
