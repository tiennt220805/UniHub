package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;
import com.unihub.workshop.application.port.out.ManagedWorkshopQueries;
import com.unihub.workshop.application.port.out.WorkshopCatalogEntry;
import com.unihub.workshop.application.port.out.WorkshopCatalogPage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The management list (Phase 8, D119): the same card projection as the public catalog, but over
 * every status and ordered newest start first. {@code titleContains} is a case-insensitive
 * substring with LIKE wildcards in user input escaped; every value is a bound parameter. The caller
 * runs it in a primary-routed transaction, so it sees its own writes.
 */
@Repository
class JdbcManagedWorkshopQueries implements ManagedWorkshopQueries {
    private final JdbcTemplate jdbcTemplate;

    JdbcManagedWorkshopQueries(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkshopCatalogPage search(ListManagedWorkshopsCriteria criteria) {
        int page = criteria.page();
        int size = criteria.size();

        var where = new StringBuilder("WHERE TRUE");
        var args = new ArrayList<Object>();
        if (criteria.status() != null) {
            where.append(" AND status = ?");
            args.add(criteria.status());
        }
        if (criteria.titleContains() != null) {
            where.append(" AND title ILIKE ? ESCAPE '\\'");
            args.add(contains(criteria.titleContains()));
        }

        Long totalElements =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM workshops " + where, Long.class, args.toArray());

        var pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<WorkshopCatalogEntry> items =
                jdbcTemplate.query(
                        "SELECT id, title, location, start_time, status, speaker_name, end_time, "
                                + "capacity, seats_taken, is_paid, price, cover_image_key "
                                + "FROM workshops "
                                + where
                                + " ORDER BY start_time DESC, id LIMIT ? OFFSET ?",
                        (rs, rowNum) ->
                                new WorkshopCatalogEntry(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("title"),
                                        rs.getString("location"),
                                        rs.getTimestamp("start_time").toInstant(),
                                        rs.getString("status"),
                                        rs.getString("speaker_name"),
                                        rs.getTimestamp("end_time").toInstant(),
                                        rs.getInt("capacity"),
                                        rs.getInt("seats_taken"),
                                        rs.getBoolean("is_paid"),
                                        rs.getBigDecimal("price"),
                                        rs.getString("cover_image_key")),
                        pageArgs.toArray());

        int totalPages = size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        return new WorkshopCatalogPage(items, page, size, totalElements, totalPages);
    }

    private static String contains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
