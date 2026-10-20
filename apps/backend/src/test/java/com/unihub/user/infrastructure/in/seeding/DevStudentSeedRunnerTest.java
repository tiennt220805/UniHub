package com.unihub.user.infrastructure.in.seeding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** AC9.1 (profile gate), AC9.2 (no password → nothing seeded, no default). */
class DevStudentSeedRunnerTest {

    private final SeedDevStudentsUseCase seed = mock(SeedDevStudentsUseCase.class);
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withBean(SeedDevStudentsUseCase.class, () -> seed)
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
}
