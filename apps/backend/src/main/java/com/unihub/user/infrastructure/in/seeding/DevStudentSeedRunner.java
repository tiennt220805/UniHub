package com.unihub.user.infrastructure.in.seeding;

import com.unihub.user.application.command.SeedDevStaffCommand;
import com.unihub.user.application.command.SeedDevStaffCommand.DevStaffAccount;
import com.unihub.user.application.command.SeedDevStaffCommand.StaffRole;
import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.command.SeedDevStudentsCommand.DevStudent;
import com.unihub.user.application.port.in.SeedDevStaffUseCase;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the dev accounts at start-up — the Students and, since Phase 7 task 5.8, one account for
 * each staff role — only under the {@code dev} profile, so a default or production run never
 * creates one (user.md §10). A group with no password configured is not seeded, and the log says
 * so; there is no fallback password. This stays the only {@code @Profile}-gated bean in {@code
 * src/main} (Phase 7 checklist D86): the cluster runs the {@code dev} profile for exactly this, so
 * any other bean added under it would be switched on there too.
 */
@Component
@Profile("dev")
@EnableConfigurationProperties(DevStudentSeedProperties.class)
class DevStudentSeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevStudentSeedRunner.class);

    private final SeedDevStudentsUseCase seed;
    private final SeedDevStaffUseCase seedStaff;
    private final DevStudentSeedProperties properties;

    DevStudentSeedRunner(
            SeedDevStudentsUseCase seed,
            SeedDevStaffUseCase seedStaff,
            DevStudentSeedProperties properties) {
        this.seed = seed;
        this.seedStaff = seedStaff;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedStudents();
        seedStaff();
    }

    private void seedStudents() {
        if (!properties.hasPassword()) {
            LOG.warn(
                    "Dev Student seeding skipped: UNIHUB_DEV_SEED_STUDENT_PASSWORD is not set."
                            + " Set it to create the dev accounts; there is no default password.");
            return;
        }
        var students =
                properties.students().stream()
                        .map(s -> new DevStudent(s.email(), s.fullName()))
                        .toList();
        var created = seed.seed(new SeedDevStudentsCommand(students, properties.studentPassword()));
        LOG.info(
                "Dev Student seeding done: {} created, {} already present.",
                created,
                students.size() - created);
    }

    private void seedStaff() {
        var accounts = new ArrayList<DevStaffAccount>();
        var unset = new LinkedHashSet<StaffRole>();
        for (var staff : properties.staff()) {
            var password = properties.passwordOf(staff.role());
            if (password == null) {
                unset.add(staff.role());
            } else {
                accounts.add(
                        new DevStaffAccount(
                                staff.email(), staff.fullName(), staff.role(), password));
            }
        }
        if (!unset.isEmpty()) {
            LOG.warn(
                    "Dev staff seeding skipped for {}: the password variable of each is not set"
                            + " (UNIHUB_DEV_SEED_ORGANIZER_PASSWORD, UNIHUB_DEV_SEED_ADMIN_PASSWORD,"
                            + " UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD); there is no default password.",
                    unset);
        }
        if (accounts.isEmpty()) {
            return;
        }
        var created = seedStaff.seed(new SeedDevStaffCommand(accounts));
        LOG.info(
                "Dev staff seeding done: {} created, {} already present.",
                created,
                accounts.size() - created);
    }
}
