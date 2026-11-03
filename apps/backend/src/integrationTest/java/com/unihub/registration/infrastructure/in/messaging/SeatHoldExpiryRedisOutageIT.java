package com.unihub.registration.infrastructure.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.unihub.registration.ShortChainITSupport;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.shared.domain.id.WorkshopId;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * M-33 × M-34, INV-07 meets INV-08 (checklist D19, AC19.1, AC19.3): the seat lock is fail-closed,
 * so a hold that expires while Redis is down cannot be released; the message must wait, in its
 * retry chain, for as long as Redis is away — far longer than the three stages that would park any
 * other message — and the seat must come back by itself once Redis does. Nothing is released
 * without the lock, and nothing ends in the parking lot.
 *
 * <p>Runs on a Redis container of its own (stopping the shared one would fail every later class),
 * with a fixed host port so the address survives a restart, and on the private short-chain broker
 * ({@link ShortChainITSupport}): the three stages are 0.3 s each, so the outage below lasts many
 * times the whole chain.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeatHoldExpiryRedisOutageIT extends ShortChainITSupport {
    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> OUTAGE_REDIS =
            new GenericContainer<>("redis:7")
                    .withExposedPorts(6379)
                    .withCreateContainerCmdModifier(
                            cmd ->
                                    cmd.getHostConfig()
                                            .withPortBindings(
                                                    new PortBinding(
                                                            Ports.Binding.bindPort(REDIS_PORT),
                                                            new ExposedPort(6379))));

    /** The longest the outage lasts: the hold (4 s) plus many whole chains (0.9 s each). */
    private static final Duration OUTAGE = HOLD.plusSeconds(6);

    /** An orphan lock from a paused Redis lives until its 5 s lease; Redisson reconnects too. */
    private static final Duration RECOVERY = Duration.ofSeconds(60);

    static {
        OUTAGE_REDIS.start();
    }

    private static int freePort() {
        return com.unihub.shared.infrastructure.FixedPorts.free();
    }

    @AfterAll
    static void stopRedis() {
        OUTAGE_REDIS.stop();
    }

    @DynamicPropertySource
    static void outageRedis(DynamicPropertyRegistry registry) {
        registry.add(
                "unihub.redis.address",
                () -> "redis://" + OUTAGE_REDIS.getHost() + ":" + REDIS_PORT);
    }

    /** How Redis is broken, and mended. */
    enum Fault {
        STOPPED {
            @Override
            void inject() {
                DockerClientFactory.instance()
                        .client()
                        .stopContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }

            @Override
            void mend() {
                DockerClientFactory.instance()
                        .client()
                        .startContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }
        },
        PAUSED {
            @Override
            void inject() {
                DockerClientFactory.instance()
                        .client()
                        .pauseContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }

            @Override
            void mend() {
                DockerClientFactory.instance()
                        .client()
                        .unpauseContainerCmd(OUTAGE_REDIS.getContainerId())
                        .exec();
            }
        };

        abstract void inject();

        abstract void mend();
    }

    /**
     * The application has no meter registry of its own yet (observability is Phase 6), so one is
     * attached to the global registry for the test, as the metrics unit tests do.
     */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void attachMeters() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detachMeters() {
        Metrics.removeRegistry(meters);
    }

    private double lockWaits() {
        var counter = meters.find("unihub.seat.hold.expiry.lock.unavailable").counter();
        return counter == null ? 0 : counter.count();
    }

    private ReserveSeatResult.Reserved awaitReserved(WorkshopId workshop) {
        return await().atMost(RECOVERY)
                .ignoreException(SeatLockUnavailableException.class)
                .until(() -> reserveForNewStudent(workshop), r -> true);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    @Timeout(300)
    @DisplayName(
            "Redis down far longer than the retry chain: the expiry waits unparked, then releases the seat")
    void redisDownLongerThanTheChain_expiryWaits_thenReleases(Fault fault) throws Exception {
        var workshop = aWorkshop(3);
        // The previous variant mended Redis a moment ago; the client reconnects with a backoff.
        var held = awaitReserved(workshop);
        var kept = awaitReserved(workshop);
        var waitsBefore = lockWaits();
        relayHold(held.id().value());

        fault.inject();
        try {
            // The hold runs out at +4 s; from then every attempt finds no lock. Stay away for many
            // times the whole chain.
            Thread.sleep(OUTAGE.toMillis());

            assertThat(registrationStatus(held.id().value()))
                    .as("nothing is released without the lock")
                    .isEqualTo("RESERVED");
            assertThat(fixtures.seatsTaken(workshop)).isEqualTo(2);
            assertThat(parkedMessages()).as("never parked").isZero();
            assertThat(lockWaits() - waitsBefore)
                    .as("every wait is counted (more than the three of the chain)")
                    .isGreaterThan(3);
        } finally {
            fault.mend();
        }

        await().atMost(RECOVERY)
                .untilAsserted(
                        () ->
                                assertThat(registrationStatus(held.id().value()))
                                        .isEqualTo("EXPIRED"));
        assertThat(fixtures.seatsTaken(workshop)).isEqualTo(1);
        assertThat(registrationStatus(kept.id().value())).isEqualTo("RESERVED");
        assertSeatsMatchHolders(workshop);
        assertThat(parkedMessages()).isZero();
    }
}
