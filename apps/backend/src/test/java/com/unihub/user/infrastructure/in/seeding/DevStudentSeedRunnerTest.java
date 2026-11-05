package com.unihub.user.infrastructure.in.seeding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.unihub.user.application.command.SeedDevStaffCommand;
import com.unihub.user.application.command.SeedDevStaffCommand.StaffRole;
import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.port.in.SeedDevStaffUseCase;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** AC9.1 (profile gate), AC9.2 (no password → nothing seeded, no default). */
class DevStudentSeedRunnerTest {

    private final SeedDevStudentsUseCase seed = mock(SeedDevStudentsUseCase.class);
    private final SeedDevStaffUseCase seedStaff = mock(SeedDevStaffUseCase.class);
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withBean(SeedDevStudentsUseCase.class, () -> seed)
                    .withBean(SeedDevStaffUseCase.class, () -> seedStaff)
                    .withUserConfiguration(DevStudentSeedRunner.class);

    @Test
    @DisplayName("AC9.1: no active profile, no seeder bean at all")
    void defaultProfile_noRunner() {
        context.withPropertyValues("unihub.dev-seed.student-password=whatever")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevStudentSeedRunner.class));
    }

    @Test
    @DisplayName("AC9.1: a production profile gets no seeder either")
    void productionProfile_noRunner() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("unihub.dev-seed.student-password=whatever")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevStudentSeedRunner.class));
    }

    @Test
    @DisplayName("AC9.2: dev profile without a password seeds nothing")
    void devWithoutPassword_seedsNothing() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            verify(seed, never()).seed(any());
                        });
    }

    @Test
    @DisplayName("AC9.2: a blank password counts as unset")
    void devWithBlankPassword_seedsNothing() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("unihub.dev-seed.student-password=   ")
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            verify(seed, never()).seed(any());
                        });
    }

    @Test
    @DisplayName("dev profile with a password seeds the default accounts with that password")
    void devWithPassword_seeds() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("unihub.dev-seed.student-password=from-env")
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            var captor = ArgumentCaptor.forClass(SeedDevStudentsCommand.class);
                            verify(seed).seed(captor.capture());
                            assertThat(captor.getValue().password()).isEqualTo("from-env");
                            assertThat(captor.getValue().students())
                                    .extracting(SeedDevStudentsCommand.DevStudent::email)
                                    .containsExactly(
                                            "student1@dev.unihub.local",
                                            "student2@dev.unihub.local",
                                            "student3@dev.unihub.local");
                        });
    }

    // ---------- the staff accounts (Phase 7 task 5.8) ----------

    @Test
    @DisplayName("no staff password set: no staff account is seeded, and the Students still are")
    void devWithoutStaffPasswords_seedsNoStaff() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("unihub.dev-seed.student-password=from-env")
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            verify(seedStaff, never()).seed(any());
                            verify(seed).seed(any());
                        });
    }

    @Test
    @DisplayName(
            "each staff group has its own password: an Organizer, an Admin and a Check-in Staff account")
    void devWithStaffPasswords_seedsOneAccountPerRole() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "unihub.dev-seed.organizer-password=organizer-pw",
                        "unihub.dev-seed.admin-password=admin-pw",
                        "unihub.dev-seed.checkin-staff-password=checkin-pw")
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            var captor = ArgumentCaptor.forClass(SeedDevStaffCommand.class);
                            verify(seedStaff).seed(captor.capture());
                            assertThat(captor.getValue().accounts())
                                    .extracting(
                                            SeedDevStaffCommand.DevStaffAccount::email,
                                            SeedDevStaffCommand.DevStaffAccount::role,
                                            SeedDevStaffCommand.DevStaffAccount::password)
                                    .containsExactly(
                                            org.assertj.core.api.Assertions.tuple(
                                                    "organizer1@dev.unihub.local",
                                                    StaffRole.ORGANIZER,
                                                    "organizer-pw"),
                                            org.assertj.core.api.Assertions.tuple(
                                                    "admin1@dev.unihub.local",
                                                    StaffRole.ADMIN,
                                                    "admin-pw"),
                                            org.assertj.core.api.Assertions.tuple(
                                                    "staff1@dev.unihub.local",
                                                    StaffRole.CHECKIN_STAFF,
                                                    "checkin-pw"));
                        });
    }

    @Test
    @DisplayName(
            "a role whose password is unset is skipped alone; there is no fallback to another role's")
    void devWithOnlyTheOrganizerPassword_seedsOnlyTheOrganizer() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "unihub.dev-seed.organizer-password=organizer-pw",
                        "unihub.dev-seed.admin-password=   ")
                .run(
                        ctx -> {
                            ctx.getBean(DevStudentSeedRunner.class)
                                    .run(new DefaultApplicationArguments());

                            var captor = ArgumentCaptor.forClass(SeedDevStaffCommand.class);
                            verify(seedStaff).seed(captor.capture());
                            assertThat(captor.getValue().accounts())
                                    .extracting(SeedDevStaffCommand.DevStaffAccount::role)
                                    .containsExactly(StaffRole.ORGANIZER);
                        });
    }

    @Test
    @DisplayName("the passwords never reach a log line through toString")
    void passwordsStayOutOfToString() {
        context.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "unihub.dev-seed.student-password=student-secret",
                        "unihub.dev-seed.organizer-password=organizer-secret")
                .run(
                        ctx -> {
                            var properties = ctx.getBean(DevStudentSeedProperties.class);

                            assertThat(properties.toString())
                                    .doesNotContain("student-secret")
                                    .doesNotContain("organizer-secret");
                            assertThat(
                                            new SeedDevStaffCommand.DevStaffAccount(
                                                            "a@dev.test",
                                                            "A",
                                                            StaffRole.ADMIN,
                                                            "admin-secret")
                                                    .toString())
                                    .doesNotContain("admin-secret");
                        });
    }
}
