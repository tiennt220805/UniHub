package com.unihub.workshop.infrastructure.out.persistence;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.SeatFigures;
import com.unihub.workshop.application.port.out.WorkshopSeatQueries;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Two columns of one row, straight from the table: no cache, no entity. */
@Repository
class JdbcWorkshopSeatQueries implements WorkshopSeatQueries {
    private final JdbcTemplate jdbc;

    JdbcWorkshopSeatQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SeatFigures> figuresOf(WorkshopId id) {
        return jdbc
                .query(
                        "SELECT capacity, seats_taken FROM workshops WHERE id = ?",
                        (rs, row) ->
                                new SeatFigures(rs.getInt("capacity"), rs.getInt("seats_taken")),
                        id.value())
                .stream()
                .findFirst();
    }
}
