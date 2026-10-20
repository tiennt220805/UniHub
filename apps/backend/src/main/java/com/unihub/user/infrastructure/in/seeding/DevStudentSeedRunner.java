package com.unihub.user.infrastructure.in.seeding;

import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.command.SeedDevStudentsCommand.DevStudent;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the dev Student accounts at start-up — only under the {@code dev} profile, so a default or
 * production run never creates one (user.md §10). With no password configured it seeds nothing and
 * says so; there is no fallback password.
 */
@Component
@Profile("dev")
@EnableConfigurationProperties(DevStudentSeedProperties.class)
class DevStudentSeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevStudentSeedRunner.class);

    private final SeedDevStudentsUseCase seed;
    private final DevStudentSeedProperties properties;

    DevStudentSeedRunner(SeedDevStudentsUseCase seed, DevStudentSeedProperties properties) {
        this.seed = seed;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
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
}
