package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.api.WorkshopSummaries;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read Notification uses for a workshop's name, times and status (Phase 4 checklist D35): the
 * data a stale reschedule is recognised by (AC35.4, AC35.5), and nothing more of the aggregate.
 */
@SpringBootTest
class WorkshopSummaryLookupIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private WorkshopSummaries summaries;
    @Autowired private WorkshopRepository workshops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private final List<WorkshopId> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM workshops WHERE id = ?", id.value()));
    }

    private Workshop saved(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        created.add(workshop.id());
        return workshop;
    }

    @Test
    @DisplayName("a published workshop: title, times, location, not cancelled")
    void published_isSummarised() {
        var start = Instant.parse("2026-12-01T09:00:00Z");
        var end = Instant.parse("2026-12-01T11:00:00Z");
        var workshop =
                saved(
                        aPublishedWorkshop()
                                .withTitle("Intro to Kubernetes")
                                .withLocation("Room 101")
                                .withStartTime(start)
                                .withEndTime(end)
                                .build());

        var summary = summaries.find(workshop.id()).orElseThrow();

        assertThat(summary.id()).isEqualTo(workshop.id());
        assertThat(summary.title()).isEqualTo("Intro to Kubernetes");
        assertThat(summary.location()).isEqualTo("Room 101");
        assertThat(summary.startTime()).isEqualTo(start);
        assertThat(summary.endTime()).isEqualTo(end);
        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    @DisplayName("a cancelled workshop is still found, flagged cancelled")
    void cancelled_isFoundAndFlagged() {
        var workshop = saved(aCancelledWorkshop().build());

        assertThat(summaries.find(workshop.id()).orElseThrow().cancelled()).isTrue();
    }

    @Test
    @DisplayName("a draft is found too: the lookup does not decide visibility")
    void draft_isFound() {
        var workshop = saved(aDraftWorkshop().build());

        var summary = summaries.find(workshop.id()).orElseThrow();

        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    @DisplayName("an unknown workshop is empty")
    void unknown_isEmpty() {
        assertThat(summaries.find(WorkshopId.newId())).isEmpty();
    }
}
