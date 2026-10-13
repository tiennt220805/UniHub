package com.unihub.shared.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} triggers. Each job's trigger is itself switched on or off by its own
 * {@code unihub.jobs.<job>.enabled} property, so enabling scheduling here starts nothing on its
 * own.
 */
@Configuration
@EnableScheduling
class SchedulingConfig {}
