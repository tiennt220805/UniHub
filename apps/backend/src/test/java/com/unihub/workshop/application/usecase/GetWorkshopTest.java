package com.unihub.workshop.application.usecase;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aCancelledWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aCompletedWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aDraftWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.port.out.WorkshopDetailSnapshot;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.application.port.out.WorkshopSeatsSnapshot;
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
    private final InMemoryWorkshopCache cache = new InMemoryWorkshopCache();
    private final GetWorkshop useCase = new GetWorkshop(workshops, cache);

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

    // ---------- cache-aside (workshop.md §2) ----------

    private com.unihub.workshop.domain.model.Workshop stored(WorkshopBuilder builder) {
        var workshop = builder.withCapacity(30).withSeatsTaken(12).build();
        when(workshops.findById(workshop.id())).thenReturn(Optional.of(workshop));
        return workshop;
    }

    private GetWorkshopQuery anonymous(com.unihub.workshop.domain.model.Workshop workshop) {
        return new GetWorkshopQuery(workshop.id().value(), Set.of());
    }

    @Test
    @DisplayName("a miss reads the database once and fills both the detail and the seats entry")
    void get_miss_readsOnceAndFillsBothEntries() {
        var workshop = stored(aPublishedWorkshop());

        useCase.get(anonymous(workshop));

        verify(workshops, times(1)).findById(workshop.id());
        assertThat(cache.details).containsKey(workshop.id().value());
        assertThat(cache.seats).containsKey(workshop.id().value());
    }

    @Test
    @DisplayName("with both entries cached the database is not read at all")
    void get_hit_doesNotTouchTheDatabase() {
        var workshop = stored(aPublishedWorkshop());
        useCase.get(anonymous(workshop));

        var again = useCase.get(anonymous(workshop));

        verify(workshops, times(1)).findById(workshop.id());
        assertThat(again.title()).isEqualTo(workshop.title());
    }

    @Test
    @DisplayName("a result served from the cache equals the one read from the database")
    void get_cachedResult_equalsTheFreshOne() {
        var workshop = stored(aPublishedWorkshop());

        var fresh = useCase.get(anonymous(workshop));
        var cached = useCase.get(anonymous(workshop));

        assertThat(cached).isEqualTo(fresh);
    }

    @Test
    @DisplayName("when only the seats entry has expired, it alone is refilled from one read")
    void get_seatsExpired_refillsSeatsOnly() {
        var workshop = stored(aPublishedWorkshop());
        useCase.get(anonymous(workshop));
        var detailBefore = cache.details.get(workshop.id().value());
        cache.seats.clear();

        useCase.get(anonymous(workshop));

        verify(workshops, times(2)).findById(workshop.id());
        assertThat(cache.seats).containsKey(workshop.id().value());
        assertThat(cache.details.get(workshop.id().value())).isSameAs(detailBefore);
    }

    @Test
    @DisplayName("when only the detail entry has expired, it alone is refilled from one read")
    void get_detailExpired_refillsDetailOnly() {
        var workshop = stored(aPublishedWorkshop());
        useCase.get(anonymous(workshop));
        var seatsBefore = cache.seats.get(workshop.id().value());
        cache.details.clear();

        useCase.get(anonymous(workshop));

        verify(workshops, times(2)).findById(workshop.id());
        assertThat(cache.details).containsKey(workshop.id().value());
        assertThat(cache.seats.get(workshop.id().value())).isSameAs(seatsBefore);
    }

    @Test
    @DisplayName("the seats entry decides capacity, seats taken, status and version")
    void get_mergesSeatsOverDetail() {
        var workshop = stored(aPublishedWorkshop());
        useCase.get(anonymous(workshop));
        var detail = cache.details.get(workshop.id().value());
        var seats = cache.seats.get(workshop.id().value());
        cache.seats.put(
                workshop.id().value(),
                new WorkshopSeatsSnapshot(
                        seats.id(), 29, 50, "CANCELLED", seats.version() + 7, seats.updatedAt()));
        cache.details.put(workshop.id().value(), detail);

        var merged = useCase.get(anonymous(workshop));

        assertThat(merged.seatsTaken()).isEqualTo(29);
        assertThat(merged.capacity()).isEqualTo(50);
        assertThat(merged.status()).isEqualTo("CANCELLED");
        assertThat(merged.version()).isEqualTo(seats.version() + 7);
        assertThat(merged.title()).isEqualTo(workshop.title());
    }

    @Test
    @DisplayName("a DRAFT cached by a manager is still 'not found' to the next anonymous caller")
    void get_cachedDraft_staysHiddenFromOthers() {
        var workshop = stored(aDraftWorkshop());
        useCase.get(new GetWorkshopQuery(workshop.id().value(), Set.of("WORKSHOP_EDIT")));
        assertThat(cache.details).containsKey(workshop.id().value());

        assertThatThrownBy(() -> useCase.get(anonymous(workshop)))
                .isInstanceOf(WorkshopNotFoundException.class);
        verify(workshops, times(1)).findById(workshop.id());
    }

    @Test
    @DisplayName("an unknown workshop is never cached: every request asks the database again")
    void get_unknown_isNotCached() {
        var id = WorkshopId.newId();
        when(workshops.findById(id)).thenReturn(Optional.empty());
        var query = new GetWorkshopQuery(id.value(), Set.of());

        assertThatThrownBy(() -> useCase.get(query)).isInstanceOf(WorkshopNotFoundException.class);
        assertThatThrownBy(() -> useCase.get(query)).isInstanceOf(WorkshopNotFoundException.class);

        verify(workshops, times(2)).findById(id);
        assertThat(cache.details).isEmpty();
        assertThat(cache.seats).isEmpty();
    }

    @Test
    @DisplayName("the cached detail carries no brochure storage key, only whether there is one")
    void get_detailSnapshot_hasNoStorageKey() {
        var workshop = stored(aPublishedWorkshop());

        useCase.get(anonymous(workshop));

        assertThat(WorkshopDetailSnapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("hasBrochure")
                .doesNotContain("brochureS3Key");
    }
}
