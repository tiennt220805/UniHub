package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-support SQL for the {@code workshops} table (the real persistence adapter arrives with the
 * Workshop use cases). {@link #insert} writes a domain {@link Workshop}; {@link #insertRaw} writes
 * raw column values so a test can bypass the domain and hit the schema's own CHECKs.
 */
public final class WorkshopRows {

    private WorkshopRows() {}

    public static void insert(JdbcTemplate jdbc, Workshop w) {
        var columns = new LinkedHashMap<String, Object>();
        columns.put("id", w.id().value());
        columns.put("title", w.title());
        columns.put("description", w.description());
        columns.put("ai_summary", w.aiSummary());
        columns.put("speaker_name", w.speakerName());
        columns.put("speaker_bio", w.speakerBio());
        columns.put("location", w.location());
        columns.put("start_time", Timestamp.from(w.startTime()));
        columns.put("end_time", Timestamp.from(w.endTime()));
        columns.put("capacity", w.capacity());
        columns.put("seats_taken", w.seatsTaken());
        columns.put("is_paid", w.isPaid());
        columns.put("price", w.price());
        columns.put("brochure_s3_key", w.brochureS3Key());
        columns.put("cover_image_key", w.coverImageKey());
        columns.put("status", w.status().name());
        columns.put("version", (int) w.version().value());
        insertColumns(jdbc, columns);
    }

    /** A legal baseline row with {@code overrides} applied on top; may be deliberately illegal. */
    public static UUID insertRaw(JdbcTemplate jdbc, Map<String, Object> overrides) {
        var id = UUID.randomUUID();
        var columns = new LinkedHashMap<String, Object>();
        columns.put("id", id);
        columns.put("title", "Raw workshop");
        columns.put("speaker_name", "Ada Lovelace");
        columns.put("location", "Room 101");
        columns.put("start_time", Timestamp.valueOf("2026-12-01 09:00:00"));
        columns.put("end_time", Timestamp.valueOf("2026-12-01 11:00:00"));
        columns.put("capacity", 10);
        columns.putAll(overrides);
        insertColumns(jdbc, columns);
        return (UUID) columns.get("id");
    }

    public static Workshop load(JdbcTemplate jdbc, WorkshopId id) {
        return jdbc.queryForObject(
                "SELECT * FROM workshops WHERE id = ?",
                (rs, rowNum) ->
                        Workshop.restore(
                                WorkshopId.of(rs.getObject("id", UUID.class)),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("ai_summary"),
                                rs.getString("speaker_name"),
                                rs.getString("speaker_bio"),
                                rs.getString("location"),
                                rs.getTimestamp("start_time").toInstant(),
                                rs.getTimestamp("end_time").toInstant(),
                                rs.getInt("capacity"),
                                rs.getInt("seats_taken"),
                                rs.getBoolean("is_paid"),
                                rs.getBigDecimal("price"),
                                rs.getString("brochure_s3_key"),
                                rs.getString("cover_image_key"),
                                WorkshopStatus.valueOf(rs.getString("status")),
                                new Version(rs.getInt("version"))),
                id.value());
    }

    private static void insertColumns(JdbcTemplate jdbc, Map<String, Object> columns) {
        var names = String.join(", ", columns.keySet());
        var placeholders =
                columns.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
        jdbc.update(
                "INSERT INTO workshops (" + names + ") VALUES (" + placeholders + ")",
                columns.values().toArray());
    }
}
