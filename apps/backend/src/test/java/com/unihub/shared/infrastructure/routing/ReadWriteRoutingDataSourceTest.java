package com.unihub.shared.infrastructure.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The routing key alone (backend-coding-standards.md §20). Supporting evidence for M-22 only: the
 * proof that queries really reach the Replica is the Master/Replica integration test (§3.8).
 */
class ReadWriteRoutingDataSourceTest {

    private final ReadWriteRoutingDataSource router =
            (ReadWriteRoutingDataSource)
                    ReadWriteRoutingDataSource.lazyRouting(
                                    mock(DataSource.class), mock(DataSource.class))
                            .getTargetDataSource();

    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        ReadWriteRoutingDataSource.restore(false);
    }

    @Test
    @DisplayName("read-only transaction → Replica")
    void readOnly_routesToReplica() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThat(router.determineCurrentLookupKey()).isEqualTo(DataSourceRoute.REPLICA);
    }

    @Test
    @DisplayName("read-only transaction + @ReadFromPrimary → Master")
    void readOnly_withMasterForced_routesToMaster() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        ReadWriteRoutingDataSource.forceMaster();

        assertThat(router.determineCurrentLookupKey()).isEqualTo(DataSourceRoute.MASTER);
    }

    @Test
    @DisplayName("read-write transaction → Master")
    void readWrite_routesToMaster() {
        assertThat(router.determineCurrentLookupKey()).isEqualTo(DataSourceRoute.MASTER);
    }

    @Test
    @DisplayName("read-write transaction + @ReadFromPrimary → Master")
    void readWrite_withMasterForced_routesToMaster() {
        ReadWriteRoutingDataSource.forceMaster();

        assertThat(router.determineCurrentLookupKey()).isEqualTo(DataSourceRoute.MASTER);
    }

    @Test
    @DisplayName("a nested @ReadFromPrimary call keeps the outer one's forcing when it returns")
    void restore_keepsOuterForcing() {
        boolean outer = ReadWriteRoutingDataSource.forceMaster();
        boolean inner = ReadWriteRoutingDataSource.forceMaster();

        ReadWriteRoutingDataSource.restore(inner);
        assertThat(ReadWriteRoutingDataSource.isMasterForced()).isTrue();

        ReadWriteRoutingDataSource.restore(outer);
        assertThat(ReadWriteRoutingDataSource.isMasterForced()).isFalse();
    }
}
