package com.unihub.shared.infrastructure.routing;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Role;

/**
 * Read/write splitting at the datasource layer (architecture.md §4.1, INV-03). The Master pool is
 * Spring Boot's own {@code spring.datasource.*}; the Replica pool is {@link
 * ReplicaDataSourceProperties}. Everything else — JPA, {@code JdbcTemplate} — sees one {@link
 * Primary} {@code DataSource}: the lazy router.
 */
@Configuration
@EnableConfigurationProperties(ReplicaDataSourceProperties.class)
class DataSourceRoutingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceRoutingConfig.class);

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource masterDataSource(DataSourceProperties master) {
        var pool = master.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("unihub-master");
        return pool;
    }

    @Bean
    @ConditionalOnProperty(prefix = "unihub.datasource.replica", name = "url")
    HikariDataSource replicaDataSource(
            DataSourceProperties master, ReplicaDataSourceProperties replica) {
        var pool =
                DataSourceBuilder.create()
                        .type(HikariDataSource.class)
                        .driverClassName(master.determineDriverClassName())
                        .url(replica.url())
                        .username(
                                replica.username() != null
                                        ? replica.username()
                                        : master.determineUsername())
                        .password(
                                replica.password() != null
                                        ? replica.password()
                                        : master.determinePassword())
                        .build();
        pool.setPoolName("unihub-replica");
        return pool;
    }

    @Bean
    @Primary
    DataSource dataSource(
            @Qualifier("masterDataSource") DataSource master,
            @Qualifier("replicaDataSource") ObjectProvider<DataSource> replica) {
        DataSource replicaTarget = replica.getIfAvailable();
        if (replicaTarget == null) {
            LOG.info(
                    "unihub.datasource.replica.url is not set: read-only transactions use the Master pool");
            replicaTarget = master;
        }
        return ReadWriteRoutingDataSource.lazyRouting(master, replicaTarget);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static ReadFromPrimaryAdvisor readFromPrimaryAdvisor() {
        return new ReadFromPrimaryAdvisor();
    }
}
