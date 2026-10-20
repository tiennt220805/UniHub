package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.Version;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.port.out.SeatCounter;
import com.unihub.workshop.application.port.out.SeatSnapshot;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The seat SQL, in one place (database-schema.md §3.1, registration.md §2). Plain JDBC rather than
 * the JPA entity: the statement is the contract, and {@code seats_taken} is not updatable through
 * the entity ({@link WorkshopJpaEntity}). It joins the caller's transaction, so the registration
 * write and this update commit together.
 */
@Repository
class JdbcSeatCounter implements SeatCounter {
    private final JdbcTemplate jdbc;

    JdbcSeatCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SeatSnapshot> read(WorkshopId workshopId) {
        return jdbc
                .query(
                        "SELECT status, is_paid, seats_taken, capacity, version "
                                + "FROM workshops WHERE id = ?",
                        (rs, row) ->
                                new SeatSnapshot(
                                        WorkshopStatus.valueOf(rs.getString("status")),
                                        rs.getBoolean("is_paid"),
                                        rs.getInt("seats_taken"),
                                        rs.getInt("capacity"),
                                        new Version(rs.getLong("version"))),
                        workshopId.value())
                .stream()
                .findFirst();
    }

    @Override
    public boolean tryClaim(WorkshopId workshopId, Version expected) {
        return jdbc.update(
                        "UPDATE workshops SET seats_taken = seats_taken + 1, version = version + 1, "
                                + "updated_at = now() "
                                + "WHERE id = ? AND version = ? AND seats_taken < capacity",
                        workshopId.value(),
                        expected.value())
                == 1;
    }

    @Override
    public boolean tryRelease(WorkshopId workshopId, Version expected) {
        return jdbc.update(
                        "UPDATE workshops SET seats_taken = seats_taken - 1, version = version + 1, "
                                + "updated_at = now() "
                                + "WHERE id = ? AND version = ? AND seats_taken > 0",
                        workshopId.value(),
                        expected.value())
                == 1;
    }
}
