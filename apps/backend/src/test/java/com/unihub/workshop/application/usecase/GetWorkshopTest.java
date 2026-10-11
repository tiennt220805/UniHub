package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopBuilder;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GetWorkshopTest {

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final GetWorkshop useCase = new GetWorkshop(workshops);

    static Stream<WorkshopBuilder> visibleToEveryone() {
        return Stream.of(aPublishedWorkshop(), aCancelledWorkshop(), aCompletedWorkshop());
    }

    @ParameterizedTest
    @MethodSource("visibleToEveryone")
    @DisplayName("PUBLISHED, CANCELLED and COMPLETED workshops are visible to an anonymous caller")
    void get_nonDraft_visibleToAnonymous(WorkshopBuilder builder) {
        var workshop = builder.withCapacity(30).withSeatsTaken(12).build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        var result = useCase.get(new GetWorkshopQuery(workshop.id().value(), Set.of()));

        assertThat(result.id()).isEqualTo(workshop.id().value());
        assertThat(result.speakerName()).isEqualTo(workshop.speakerName());
        assertThat(result.location()).isEqualTo(workshop.location());
        assertThat(result.capacity()).isEqualTo(30);
        assertThat(result.seatsTaken()).isEqualTo(12);
        assertThat(result.status()).isEqualTo(workshop.status().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKSHOP_CREATE", "WORKSHOP_EDIT"})
    @DisplayName("a DRAFT workshop is visible to a caller holding WORKSHOP_CREATE or WORKSHOP_EDIT")
    void get_draft_visibleToManagers(String permission) {
        var workshop = aDraftWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));

        var result = useCase.get(new GetWorkshopQuery(workshop.id().value(), Set.of(permission)));

        assertThat(result.status()).isEqualTo("DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "CHECKIN_SCAN", "REPORT_VIEW"})
    @DisplayName(
            "a DRAFT workshop is 'not found' to anyone without a workshop-management permission")
    void get_draft_hiddenFromOthers(String permission) {
        var workshop = aDraftWorkshop().build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        Set<String> permissions = permission.isEmpty() ? Set.of() : Set.of(permission);

        assertThatThrownBy(
                        () -> useCase.get(new GetWorkshopQuery(workshop.id().value(), permissions)))
                .isInstanceOf(WorkshopNotFoundException.class);
    }

    @Test
    @DisplayName("an unknown id is 'not found'")
    void get_unknown_notFound() {
        var id = WorkshopId.newId();
        when(workshops.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.get(new GetWorkshopQuery(id.value(), Set.of())))
                .isInstanceOf(WorkshopNotFoundException.class);
    }
}
