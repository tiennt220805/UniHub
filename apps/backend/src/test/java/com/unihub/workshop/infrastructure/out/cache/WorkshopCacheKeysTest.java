package com.unihub.workshop.infrastructure.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkshopCacheKeysTest {

    private static SearchWorkshopsCriteria criteria(String q, String speaker, String location) {
        return new SearchWorkshopsCriteria(q, speaker, location, null, null, 0, 20);
    }

    @Test
    @DisplayName(
            "detail and seats keys are the documented ones; detail and list carry the shape version"
                    + " (distributed-coordination.md §5, INV-15)")
    void keyFormats() {
        var id = new WorkshopId(UUID.fromString("11111111-2222-3333-4444-555555555555"));

        assertThat(WorkshopCacheKeys.detail(id))
                .isEqualTo("unihub:cache:workshop:detail:v2:11111111-2222-3333-4444-555555555555");
        assertThat(WorkshopCacheKeys.seats(id))
                .isEqualTo("unihub:cache:workshop:seats:11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("the list key is the prefix plus a 32-hex-character filter hash")
    void listKeyShape() {
        assertThat(WorkshopCacheKeys.list(criteria("kube", null, null)))
                .matches("unihub:cache:workshop:list:v2:[0-9a-f]{32}");
    }

    @Test
    @DisplayName("the same query always maps to the same key")
    void deterministic() {
        assertThat(WorkshopCacheKeys.list(criteria("kube", "ada", null)))
                .isEqualTo(WorkshopCacheKeys.list(criteria("kube", "ada", null)));
    }

    @Test
    @DisplayName("every parameter is part of the key")
    void everyParameterMatters() {
        var base = criteria("kube", "ada", "room");
        var from = Instant.parse("2026-01-01T00:00:00Z");
        var others =
                java.util.List.of(
                        criteria("kub", "ada", "room"),
                        criteria("kube", "adb", "room"),
                        criteria("kube", "ada", "rooms"),
                        new SearchWorkshopsCriteria("kube", "ada", "room", from, null, 0, 20),
                        new SearchWorkshopsCriteria("kube", "ada", "room", null, from, 0, 20),
                        new SearchWorkshopsCriteria("kube", "ada", "room", null, null, 1, 20),
                        new SearchWorkshopsCriteria("kube", "ada", "room", null, null, 0, 50));

        for (var other : others) {
            assertThat(WorkshopCacheKeys.filterHash(other))
                    .as(other.toString())
                    .isNotEqualTo(WorkshopCacheKeys.filterHash(base));
        }
    }

    @Test
    @DisplayName("values cannot run into each other: q=\"a;b\" is not q=\"a\", speaker=\"b\"")
    void fieldsCannotCollide() {
        assertThat(WorkshopCacheKeys.filterHash(criteria("a;b", null, null)))
                .isNotEqualTo(WorkshopCacheKeys.filterHash(criteria("a", "b", null)));
        assertThat(WorkshopCacheKeys.filterHash(criteria("1:a", null, null)))
                .isNotEqualTo(WorkshopCacheKeys.filterHash(criteria("a", null, null)));
    }

    @Test
    @DisplayName("an absent filter differs from an empty one")
    void absentIsNotEmpty() {
        assertThat(WorkshopCacheKeys.filterHash(criteria(null, null, null)))
                .isNotEqualTo(WorkshopCacheKeys.filterHash(criteria("", null, null)));
    }
}
