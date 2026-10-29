package com.unihub.notification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.UniHubApplication;
import com.unihub.notification.application.port.out.NotificationChannel;
import com.unihub.notification.application.port.out.NotificationDeliveryResult;
import com.unihub.notification.application.port.out.NotificationDispatch;
import com.unihub.notification.domain.model.NotificationType;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The SMTP host is required configuration (Phase 4 checklist D36, AC36.3): without it the
 * application stops at start with a message that says what to set; a host that is set but nothing
 * listens on starts it, and the channel then reports {@code UNAVAILABLE} per delivery. The same
 * rule as Redis (D21), for the same reason: a misconfigured environment must not quietly turn every
 * email into a retry.
 */
class SmtpRequiredStartupIT extends IntegrationTestSupport {

    private SpringApplicationBuilder application() {
        // command-line arguments outrank the system property IntegrationTestSupport sets
        return new SpringApplicationBuilder(UniHubApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "unihub.security.jwt.secret=" + TestJwts.SECRET);
    }

    @Test
    @DisplayName("AC36.3: no SMTP host, no start, and the message says what to set")
    void missingHost_stopsTheApplication() {
        assertThatThrownBy(() -> application().run("--unihub.notification.smtp.host="))
                .hasStackTraceContaining("unihub.notification.smtp.host is required")
                .hasStackTraceContaining("UNIHUB_NOTIFICATION_SMTP_HOST");
    }

    @Test
    @DisplayName("a channel configured for a type but implemented by no bean stops the start")
    void configuredChannelWithoutABean_stopsTheApplication() {
        assertThatThrownBy(
                        () ->
                                application()
                                        .run(
                                                "--unihub.notification.channels.workshop-cancelled=EMAIL,IN_APP,CARRIER_PIGEON"))
                .hasStackTraceContaining("CARRIER_PIGEON")
                .hasStackTraceContaining("no NotificationChannel bean handles it");
    }

    /** Own context: the host is an address nothing listens on. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class UnreachableHost {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
            registry.add("unihub.notification.smtp.host", () -> "127.0.0.1");
            registry.add("unihub.notification.smtp.port", () -> "1");
        }

        @Autowired private List<NotificationChannel> channels;

        @Test
        @DisplayName(
                "AC36.3: a host nothing listens on still starts the application; EMAIL is UNAVAILABLE")
        void startsAndTheChannelIsUnavailable() {
            var email =
                    channels.stream()
                            .filter(c -> c.channelCode().equals("EMAIL"))
                            .findFirst()
                            .orElseThrow();

            var result =
                    email.send(
                            new NotificationDispatch(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    "ann@example.test",
                                    NotificationType.WORKSHOP_CANCELLED,
                                    Map.of(
                                            "recipientName", "An",
                                            "workshopTitle", "W",
                                            "startTime", Instant.parse("2026-12-01T01:00:00Z"),
                                            "location", "R1")));

            assertThat(result).isEqualTo(NotificationDeliveryResult.UNAVAILABLE);
        }
    }
}
