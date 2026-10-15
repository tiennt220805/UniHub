package com.unihub.shared.infrastructure.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.routing.ReadFromPrimary;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice test: real Spring transaction proxies, the production advisor and lazy router, mock
 * Master/Replica pools. Checks which pool is asked for the physical connection — which also fails
 * if the lazy proxy is missing, since the connection would then be taken before the read-only flag
 * is set. Supporting evidence for M-22 only (see {@link ReadWriteRoutingDataSourceTest}).
 */
class ReadFromPrimaryRoutingTest {

    private DataSource master;
    private DataSource replica;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void startContext() throws SQLException {
        master = connectingDataSource();
        replica = connectingDataSource();
        context = new AnnotationConfigApplicationContext();
        context.registerBean("masterPool", DataSource.class, () -> master);
        context.registerBean("replicaPool", DataSource.class, () -> replica);
        context.register(SliceConfig.class);
        context.refresh();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    @DisplayName("@Transactional(readOnly = true) reaches the Replica")
    void readOnly_reachesReplica() throws SQLException {
        context.getBean(Probe.class).readOnly();

        verify(replica).getConnection();
        verifyNoInteractions(master);
    }

    @Test
    @DisplayName(
            "@Transactional(readOnly = true) + @ReadFromPrimary on the method reaches the Master")
    void readFromPrimaryMethod_reachesMaster() throws SQLException {
        context.getBean(Probe.class).readOnlyFromPrimary();

        verify(master).getConnection();
        verifyNoInteractions(replica);
    }

    @Test
    @DisplayName("@ReadFromPrimary on the class reaches the Master")
    void readFromPrimaryClass_reachesMaster() throws SQLException {
        context.getBean(PrimaryProbe.class).readOnly();

        verify(master).getConnection();
        verifyNoInteractions(replica);
    }

    @Test
    @DisplayName("a read-write transaction reaches the Master")
    void readWrite_reachesMaster() throws SQLException {
        context.getBean(Probe.class).readWrite();

        verify(master).getConnection();
        verifyNoInteractions(replica);
    }

    @Test
    @DisplayName("the Master forcing ends with the call, even when it throws")
    void readFromPrimary_clearsForcingAfterFailure() {
        assertThatThrownBy(() -> context.getBean(Probe.class).failingReadFromPrimary())
                .isInstanceOf(IllegalStateException.class);

        assertThat(ReadWriteRoutingDataSource.isMasterForced()).isFalse();
    }

    private static DataSource connectingDataSource() throws SQLException {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> mock(Connection.class));
        return dataSource;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class SliceConfig {

        @Bean
        DataSource dataSource(BeanFactory beans) {
            return ReadWriteRoutingDataSource.lazyRouting(
                    beans.getBean("masterPool", DataSource.class),
                    beans.getBean("replicaPool", DataSource.class));
        }

        @Bean
        DataSourceTransactionManager transactionManager(BeanFactory beans) {
            return new DataSourceTransactionManager(beans.getBean("dataSource", DataSource.class));
        }

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static ReadFromPrimaryAdvisor readFromPrimaryAdvisor() {
            return DataSourceRoutingConfig.readFromPrimaryAdvisor();
        }

        @Bean
        Probe probe(BeanFactory beans) {
            return new Probe(beans.getBean("dataSource", DataSource.class));
        }

        @Bean
        PrimaryProbe primaryProbe(BeanFactory beans) {
            return new PrimaryProbe(beans.getBean("dataSource", DataSource.class));
        }
    }

    /** Runs one statement, which is what makes the lazy proxy fetch a physical connection. */
    static void runStatement(DataSource dataSource) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            connection.prepareStatement("SELECT 1");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    static class Probe {
        private final DataSource dataSource;

        Probe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Transactional(readOnly = true)
        public void readOnly() {
            runStatement(dataSource);
        }

        @ReadFromPrimary
        @Transactional(readOnly = true)
        public void readOnlyFromPrimary() {
            assertThat(ReadWriteRoutingDataSource.isMasterForced()).isTrue();
            runStatement(dataSource);
        }

        @Transactional
        public void readWrite() {
            runStatement(dataSource);
        }

        @ReadFromPrimary
        @Transactional(readOnly = true)
        public void failingReadFromPrimary() {
            throw new IllegalStateException("boom");
        }
    }

    @ReadFromPrimary
    static class PrimaryProbe {
        private final DataSource dataSource;

        PrimaryProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Transactional(readOnly = true)
        public void readOnly() {
            runStatement(dataSource);
        }
    }
}
