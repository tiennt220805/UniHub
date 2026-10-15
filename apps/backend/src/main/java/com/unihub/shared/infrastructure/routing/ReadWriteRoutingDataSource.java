package com.unihub.shared.infrastructure.routing;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Picks the Master or the Replica for each physical connection (backend-coding-standards.md §20):
 *
 * <table>
 *   <caption>Routing rule</caption>
 *   <tr><td>{@code @Transactional(readOnly = true)}</td><td>Replica</td></tr>
 *   <tr><td>{@code readOnly = true} + {@code @ReadFromPrimary}</td><td>Master</td></tr>
 *   <tr><td>read-write transaction, or no transaction</td><td>Master</td></tr>
 * </table>
 *
 * <p>Only usable behind {@link #lazyRouting}: see there.
 */
final class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(ReadWriteRoutingDataSource.class);

    /** Set while a {@code @ReadFromPrimary} method runs on this thread. */
    private static final ThreadLocal<Boolean> MASTER_FORCED = new ThreadLocal<>();

    private ReadWriteRoutingDataSource(DataSource master, DataSource replica) {
        setTargetDataSources(
                Map.of(DataSourceRoute.MASTER, master, DataSourceRoute.REPLICA, replica));
        setDefaultTargetDataSource(master);
        setLenientFallback(false);
        afterPropertiesSet();
    }

    /**
     * The application's {@code DataSource}: the router wrapped in a {@link
     * LazyConnectionDataSourceProxy}. Without the lazy proxy, the transaction manager takes the
     * physical connection before the transaction's read-only flag is set, so every query would go
     * to the Master. The proxy only fetches it at the first statement, when the flag is known.
     *
     * <p>The defaults are pinned (HikariCP's auto-commit, PostgreSQL's READ COMMITTED) so the proxy
     * never opens a connection just to discover them.
     */
    static LazyConnectionDataSourceProxy lazyRouting(DataSource master, DataSource replica) {
        var proxy =
                new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(master, replica));
        proxy.setDefaultAutoCommit(true);
        proxy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return proxy;
    }

    static DataSourceRoute route(boolean readOnlyTransaction, boolean masterForced) {
        return readOnlyTransaction && !masterForced
                ? DataSourceRoute.REPLICA
                : DataSourceRoute.MASTER;
    }

    /** Forces the Master on this thread; returns the previous state for {@link #restore}. */
    static boolean forceMaster() {
        boolean previous = isMasterForced();
        MASTER_FORCED.set(Boolean.TRUE);
        return previous;
    }

    static void restore(boolean previouslyForced) {
        if (!previouslyForced) {
            MASTER_FORCED.remove();
        }
    }

    static boolean isMasterForced() {
        return Boolean.TRUE.equals(MASTER_FORCED.get());
    }

    @Override
    protected DataSourceRoute determineCurrentLookupKey() {
        var route =
                route(
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        isMasterForced());
        LOG.debug("Routing connection to {}", route);
        return route;
    }
}
