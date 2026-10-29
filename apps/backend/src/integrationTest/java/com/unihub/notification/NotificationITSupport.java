package com.unihub.notification;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link NotificationITBase} on the singleton Postgres' shared database, which is what almost every
 * Notification IT wants. A class that needs a database of its own extends the base instead and
 * registers its own {@code spring.datasource.*}: two {@code @DynamicPropertySource} methods that
 * set the same key do not have a defined winner, so the shared URL cannot sit in the base.
 */
public abstract class NotificationITSupport extends NotificationITBase {

    @DynamicPropertySource
    static void sharedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
