package com.unihub.shared.infrastructure.routing;

import com.unihub.shared.infrastructure.ReplicaIntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The configuration the Check-in ITs that need a lagging Replica share. It is inherited, so every
 * subclass has the same context key and Spring reuses one application context for all of them
 * instead of booting another (each cached context keeps a connection pool open).
 */
abstract class CheckInReplicaITSupport extends ReplicaIntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "unihub.datasource.replica.url", ReplicaIntegrationTestSupport::replicaJdbcUrl);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
        registry.add("unihub.jobs.idempotency-cleanup.enabled", () -> "false");
    }
}
