package com.unihub.shared.infrastructure.out.persistence;

import com.unihub.shared.application.port.out.ReplicaLag;
import java.util.OptionalDouble;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Asked of the Replica pool itself, not of the routing datasource: outside a transaction that one
 * sends the statement to the Master, which would answer "not in recovery". With no Replica
 * configured (local, CI) the Master pool is asked and the answer is always empty.
 */
@Repository
class JdbcReplicaLag implements ReplicaLag {
    private final JdbcTemplate jdbc;

    JdbcReplicaLag(
            @Qualifier("masterDataSource") DataSource master,
            @Qualifier("replicaDataSource") ObjectProvider<DataSource> replica) {
        this.jdbc = new JdbcTemplate(replica.getIfAvailable(() -> master));
    }

    @Override
    public OptionalDouble lagSeconds() {
        var seconds =
                jdbc.queryForObject(
                        "SELECT CASE WHEN pg_is_in_recovery() THEN"
                                + " EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))::float8"
                                + " END",
                        Double.class);
        return seconds == null ? OptionalDouble.empty() : OptionalDouble.of(Math.max(0.0, seconds));
    }
}
