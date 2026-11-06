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
 *
 * <p>{@code now() - pg_last_xact_replay_timestamp()} keeps growing on a healthy Replica while the
 * primary has no writes, so a replica that has replayed everything it received reports 0 (Phase 7
 * checklist D99, because {@code overlays/loadtest} runs a real Replica). A Replica that has
 * replayed no transaction yet stays "unknown", as before. Known limit: the receive position is what
 * the Replica was <em>sent</em>; a Replica whose connection to the primary is down has replayed all
 * it received and reads 0 although the primary moved on.
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
                        "SELECT CASE WHEN pg_is_in_recovery() THEN CASE"
                                + " WHEN pg_last_xact_replay_timestamp() IS NULL THEN NULL"
                                + " WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0"
                                + " ELSE EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))"
                                + " END END::float8",
                        Double.class);
        return seconds == null ? OptionalDouble.empty() : OptionalDouble.of(Math.max(0.0, seconds));
    }
}
