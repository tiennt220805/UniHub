package com.unihub.registration.infrastructure.out.lock;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.registration.RegistrationContention;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.infrastructure.MultiInstanceHarness;
import com.unihub.shared.infrastructure.MultiInstanceHarness.Locking;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M-32, INV-06 (REQ-2.6.2; checklist D22): the seat lock is recognized across application
 * instances. Two instances — separate contexts, a {@code RedissonClient} each — are asked for the
 * last seat of one workshop at the same time, half the students on each.
 *
 * <p>Two claims, and the second is the one that matters. First, the outcome: exactly one student
 * gets the last seat, everyone else is told sold out, and the database agrees. That holds with no
 * lock at all (the version check and the CHECK see to it), so on its own it would pass while the
 * lock did nothing. Second, the lock itself: a recording decorator around each instance's {@code
 * SeatLock} logs every hold as an interval in one JVM-wide log, and no two holds of the same
 * workshop's key overlap in time. Each hold is padded so that a pair of claims cannot slip past
 * each other by luck.
 *
 * <p>The control runs the same scenario with an in-process lock in each instance. It must show
 * overlapping holds — and still the right outcome, which is the database layer doing the work the
 * lock did not. If the control shows no overlap, the harness is not separating the instances and
 * the Redisson result proves nothing.
 */
class SeatLockMultiInstanceIT extends IntegrationTestSupport {
    private static final int CAPACITY = 4;
    private static final int SEATS_LEFT = 1;
    private static final int RACERS = 8;
    // 8 racers one after another stay under the 2s wait; far below the 5s lease
    private static final Duration SEAT_HOLD = Duration.ofMillis(100);

    @Test
    @Timeout(240)
    @DisplayName(
            "with the Redisson lock, two instances never hold the same workshop's lock at once")
    void redisson_lastSeat_noTwoHoldsOverlap() throws Exception {
        try (var instances =
                MultiInstanceHarness.start(2, Locking.REDISSON, Duration.ZERO, SEAT_HOLD)) {
            assertThat(instances.bean(0, RedissonClient.class).getId())
                    .as("each instance has its own Redis client")
                    .isNotEqualTo(instances.bean(1, RedissonClient.class).getId());

            contendForTheLastSeat(instances);

            var log = instances.log();
            assertThat(log.overlaps()).as("holds of one workshop in force together").isEmpty();
            // the requests really went through both instances' locks
            assertThat(log.holdsOf(0)).as("instance 0 took the lock").isNotEmpty();
            assertThat(log.holdsOf(1)).as("instance 1 took the lock").isNotEmpty();
        }
    }

    @Test
    @Timeout(240)
    @DisplayName("control: with a lock per instance in memory, the holds overlap")
    void control_inProcessLocks_overlap() throws Exception {
        try (var instances =
                MultiInstanceHarness.start(2, Locking.IN_PROCESS, Duration.ZERO, SEAT_HOLD)) {
            contendForTheLastSeat(instances);

            assertThat(instances.log().overlaps())
                    .as("two instances sharing no lock must hold the workshop together")
                    .isNotEmpty();
            assertThat(instances.log().totalSkips()).as("nobody was ever refused").isZero();
        }
    }

    /** Runs the race and checks the outcome; the outcome is right in both variants. */
    private static void contendForTheLastSeat(MultiInstanceHarness instances) throws Exception {
        var jdbc = instances.bean(0, JdbcTemplate.class);
        var fixtures = new RegistrationFixtures(jdbc);
        try {
            var tx = instances.bean(0, TransactionTemplate.class);
            var workshops = instances.bean(0, WorkshopRepository.class);
            var workshop = aPublishedWorkshop().withCapacity(CAPACITY).build();
            tx.executeWithoutResult(s -> workshops.save(workshop));
            var id = fixtures.track(workshop.id());

            var urls = List.of(url(instances, 0), url(instances, 1));
            for (int i = 0; i < CAPACITY - SEATS_LEFT; i++) {
                RegistrationContention.registerAlone(urls.get(i % 2), fixtures.aStudent(), id);
            }
            var students = new ArrayList<UserId>();
            for (int i = 0; i < RACERS; i++) {
                students.add(fixtures.aStudent());
            }

            var run = RegistrationContention.race(urls, id, students);

            RegistrationContention.assertConverged(run, fixtures, id, CAPACITY, SEATS_LEFT);
            assertThat(run.maxInFlight())
                    .as("requests in flight at once")
                    .isGreaterThan(RACERS / 2);
        } finally {
            fixtures.cleanUp();
        }
    }

    private static String url(MultiInstanceHarness instances, int instance) {
        return "http://localhost:" + instances.port(instance);
    }
}
