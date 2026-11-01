package com.unihub.shared.infrastructure.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.checkin.CheckInFixtures;
import com.unihub.checkin.application.command.ScanSubmission;
import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.port.in.GetWorkshopReportUseCase;
import com.unihub.checkin.application.port.in.ListWorkshopAttendancesUseCase;
import com.unihub.checkin.application.port.in.SyncScanBatchUseCase;
import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The report reads from the Replica (Phase 5 checklist task 5.2, D46, D62; backend-coding-
 * standards.md §20): with replay paused, a registration confirmed, a seat taken and a check-in
 * synced on the Master are not in the report yet, and are once the Replica has caught up. Each kind
 * of figure has its own read (Registration's counts, Workshop's seat columns, Check-in's
 * attendances), so each one is moved while the Replica is behind. Removing the read-only
 * transaction from the report, or sending any of the three reads to the Master, turns this red.
 *
 * <p>The workshop is on the Replica <em>before</em> replay is paused: otherwise the report would be
 * a 404 and the test would be about something else.
 */
@SpringBootTest
class WorkshopReportReadsReplicaIT extends CheckInReplicaITSupport {

    @Autowired private GetWorkshopReportUseCase report;
    @Autowired private ListWorkshopAttendancesUseCase attendances;
    @Autowired private SyncScanBatchUseCase sync;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private CheckInFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new CheckInFixtures(jdbc, workshops, new TransactionTemplate(transactions));
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private int rowsOnReplica(String sql, Object... args) {
        var readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return readOnly.execute(s -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    @DisplayName(
            "a confirmation, a taken seat and a check-in written a moment ago are not in the report"
                    + " while the Replica is behind, and are once it has caught up")
    void report_isReadFromTheReplica() throws Exception {
        var workshop = fixtures.aWorkshop(30);
        var staff = fixtures.aStaffMember();
        var attending = fixtures.aConfirmedTicket(workshop);
        awaitReplicaCaughtUp();
        var before = report.report(workshop);
        assertThat(before.confirmedCount())
                .as("control: the workshop is on the Replica")
                .isEqualTo(1);

        withReplayPaused(
                () -> {
                    fixtures.aConfirmedTicket(workshop);
                    jdbc.update(
                            "UPDATE workshops SET seats_taken = 2 WHERE id = ?", workshop.value());
                    sync.sync(
                            new SyncScanBatchCommand(
                                    new Actor.UserActor(staff, Set.of("CHECKIN_SCAN")),
                                    List.of(
                                            ScanSubmission.of(
                                                    UUID.randomUUID(),
                                                    "VERIFIED",
                                                    attending.code(),
                                                    workshop.value(),
                                                    Instant.now(),
                                                    "dev-1"))));

                    assertThat(
                                    rowsOnReplica(
                                            "SELECT count(*) FROM registrations WHERE"
                                                    + " workshop_id = ?",
                                            workshop.value()))
                            .as("control: the Replica has not seen the new registration")
                            .isEqualTo(1);
                    assertThat(rowsOnReplica("SELECT count(*) FROM attendances"))
                            .as("control: the Replica has not seen the check-in")
                            .isZero();

                    var lagging = report.report(workshop);
                    assertThat(lagging.confirmedCount())
                            .as("Registration's count is the Replica's")
                            .isEqualTo(1);
                    assertThat(lagging.seatsTaken())
                            .as("Workshop's seat column is the Replica's")
                            .isZero();
                    assertThat(lagging.checkedInCount())
                            .as("Check-in's attendance count is the Replica's")
                            .isZero();
                    assertThat(
                                    attendances
                                            .list(new ListWorkshopAttendancesQuery(workshop, 0, 20))
                                            .totalElements())
                            .as("and so is the list")
                            .isZero();
                });

        awaitReplicaCaughtUp();
        var caughtUp = report.report(workshop);
        assertThat(caughtUp.confirmedCount()).isEqualTo(2);
        assertThat(caughtUp.seatsTaken()).isEqualTo(2);
        assertThat(caughtUp.checkedInCount()).isEqualTo(1);
        assertThat(
                        attendances
                                .list(new ListWorkshopAttendancesQuery(workshop, 0, 20))
                                .totalElements())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a workshop that is not on the Replica yet is not found, not a report of zeros")
    void workshopNotYetOnTheReplica_isNotFound() throws Exception {
        awaitReplicaCaughtUp();

        withReplayPaused(
                () -> {
                    var fresh = fixtures.aWorkshop(10);

                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> report.report(fresh))
                            .as(
                                    "control for the first test: this is what 'not on the Replica' means")
                            .isInstanceOf(
                                    com.unihub.checkin.domain.exception
                                            .ReportedWorkshopNotFoundException.class);
                });
    }
}
