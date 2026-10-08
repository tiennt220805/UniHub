package com.unihub.workshop.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.security.Actor;
import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import com.unihub.workshop.domain.model.Workshop;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CreateWorkshopTest {

    private static final Actor ACTOR =
            new Actor.UserActor(UserId.newId(), Set.of("WORKSHOP_CREATE"));
    private static final Instant START = Instant.parse("2026-12-01T09:00:00Z");

    private final WorkshopRepository workshops = mock(WorkshopRepository.class);
    private final CreateWorkshop useCase = new CreateWorkshop(workshops);

    private CreateWorkshopCommand command(
            Instant start, Instant end, boolean paid, BigDecimal price) {
        return new CreateWorkshopCommand(
                ACTOR,
                "Intro to Kubernetes",
                "A hands-on introduction.",
                "Ada Lovelace",
                null,
                "Room 101",
                start,
                end,
                50,
                paid,
                price);
    }

    @Test
    @DisplayName("creates a DRAFT workshop with no seats taken and saves it")
    void create_savesDraft() {
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = useCase.create(command(START, START.plusSeconds(3600), false, null));

        var saved = ArgumentCaptor.forClass(Workshop.class);
        verify(workshops).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(WorkshopStatus.DRAFT);
        assertThat(saved.getValue().seatsTaken()).isZero();
        assertThat(result.id()).isEqualTo(saved.getValue().id().value());
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.title()).isEqualTo("Intro to Kubernetes");
        assertThat(result.paid()).isFalse();
        assertThat(result.price()).isNull();
        assertThat(result.hasBrochure()).isFalse();
    }

    @Test
    @DisplayName("creates a paid workshop carrying its price")
    void create_paid() {
        when(workshops.save(any())).thenAnswer(i -> i.getArgument(0));

        var result =
                useCase.create(
                        command(START, START.plusSeconds(3600), true, new BigDecimal("150000.00")));

        assertThat(result.paid()).isTrue();
        assertThat(result.price()).isEqualByComparingTo("150000.00");
    }

    @Test
    @DisplayName("an invalid workshop is rejected by the domain and never saved")
    void create_invalid_neverSaved() {
        assertThatThrownBy(() -> useCase.create(command(START, START, false, null)))
                .isInstanceOf(InvalidWorkshopException.class);
        assertThatThrownBy(() -> useCase.create(command(START, START.plusSeconds(60), true, null)))
                .isInstanceOf(InvalidWorkshopException.class);

        verify(workshops, never()).save(any());
    }
}
