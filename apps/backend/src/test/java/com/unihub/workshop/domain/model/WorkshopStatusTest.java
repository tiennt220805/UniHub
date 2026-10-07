package com.unihub.workshop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkshopStatusTest {

    @Test
    @DisplayName("COMPLETED and CANCELLED are terminal; DRAFT and PUBLISHED are not")
    void isTerminal_onlyForCompletedAndCancelled() {
        assertThat(WorkshopStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(WorkshopStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(WorkshopStatus.DRAFT.isTerminal()).isFalse();
        assertThat(WorkshopStatus.PUBLISHED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("enum names match the workshops.status CHECK values exactly")
    void names_matchSchemaCheckValues() {
        assertThat(WorkshopStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "CANCELLED", "COMPLETED");
    }
}
