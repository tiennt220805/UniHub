package com.unihub.user.infrastructure.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.unihub.shared.application.port.out.JobLock;
import com.unihub.shared.infrastructure.in.scheduling.JobCycle;
import com.unihub.user.application.port.in.RunNightlySyncUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** {@code unihub.jobs.nightly-sync.enabled} decides whether the trigger bean exists at all. */
class NightlySyncSchedulerConditionTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withBean(JobLock.class, () -> mock(JobLock.class))
                    .withBean(JobCycle.class, JobCycle::untraced)
                    .withBean(RunNightlySyncUseCase.class, () -> mock(RunNightlySyncUseCase.class))
                    .withUserConfiguration(NightlySyncScheduler.class);

    @Test
    @DisplayName("enabled=false: no scheduler bean, so nothing is ever triggered")
    void disabled_noBean() {
        context.withPropertyValues("unihub.jobs.nightly-sync.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(NightlySyncScheduler.class));
    }

    @Test
    @DisplayName("property absent: off by default")
    void absent_noBean() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(NightlySyncScheduler.class));
    }

    @Test
    @DisplayName("enabled=true: the scheduler bean exists")
    void enabled_beanExists() {
        context.withPropertyValues("unihub.jobs.nightly-sync.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(NightlySyncScheduler.class));
    }
}
