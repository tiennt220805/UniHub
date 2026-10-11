package com.unihub.workshop.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.command.GetWorkshopQuery;
import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.in.CancelWorkshopUseCase;
import com.unihub.workshop.application.port.in.CreateWorkshopUseCase;
import com.unihub.workshop.application.port.in.GetWorkshopUseCase;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// Web slice (backend-coding-standards.md §27 #7): the use case is mocked, no container, no
// database.
// Pins the request-shape rules packages/api-contract/common/pagination.yaml states for
// `page`/`size`.
@WebMvcTest(WorkshopController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class WorkshopControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SearchWorkshopsUseCase searchWorkshops;
    // The controller also serves the write endpoints; this class only exercises the search one.
    @MockBean private CreateWorkshopUseCase createWorkshop;
    @MockBean private UpdateWorkshopUseCase updateWorkshop;
    @MockBean private CancelWorkshopUseCase cancelWorkshop;
    @MockBean private GetWorkshopUseCase getWorkshop;

    @ParameterizedTest(name = "page={0}, size={1} → 400 problem+json, use case never called")
    @CsvSource({"0,0", "0,101", "0,-1", "-1,20"})
    @DisplayName("GET /api/v1/workshops rejects page/size outside the contract's bounds")
    void search_rejectsOutOfRangePaging(String page, String size) throws Exception {
        mockMvc.perform(get("/api/v1/workshops").param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.instance").value("/api/v1/workshops"));

        verifyNoInteractions(searchWorkshops);
    }

    @Test
    @DisplayName("GET /api/v1/workshops rejects a non-numeric size with 400 problem+json")
    void search_rejectsNonNumericSize() throws Exception {
        mockMvc.perform(get("/api/v1/workshops").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(searchWorkshops);
    }

    @ParameterizedTest(name = "page={0}, size={1} → 200")
    @CsvSource({"0,1", "0,100", "5,20"})
    @DisplayName("GET /api/v1/workshops accepts the bounds themselves and passes them on")
    void search_acceptsBoundaryPaging(int page, int size) throws Exception {
        when(searchWorkshops.search(any()))
                .thenReturn(new SearchWorkshopsResult(List.of(), page, size, 0, 0));

        mockMvc.perform(
                        get("/api/v1/workshops")
                                .param("page", String.valueOf(page))
                                .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        var criteria = ArgumentCaptor.forClass(SearchWorkshopsCriteria.class);
        verify(searchWorkshops).search(criteria.capture());
        assertThat(criteria.getValue().page()).isEqualTo(page);
        assertThat(criteria.getValue().size()).isEqualTo(size);
    }

    @Test
    @DisplayName("GET /api/v1/workshops with no paging params defaults to page=0, size=20")
    void search_defaultsPaging() throws Exception {
        when(searchWorkshops.search(any()))
                .thenReturn(new SearchWorkshopsResult(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/workshops")).andExpect(status().isOk());

        var criteria = ArgumentCaptor.forClass(SearchWorkshopsCriteria.class);
        verify(searchWorkshops).search(criteria.capture());
        assertThat(criteria.getValue().page()).isZero();
        assertThat(criteria.getValue().size()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /api/v1/workshops passes q, speaker, location and the date range on")
    void search_passesFiltersOn() throws Exception {
        when(searchWorkshops.search(any()))
                .thenReturn(new SearchWorkshopsResult(List.of(), 0, 20, 0, 0));

        mockMvc.perform(
                        get("/api/v1/workshops")
                                .param("q", "kubernetes")
                                .param("speaker", "Ada")
                                .param("location", "Room 1")
                                .param("dateFrom", "2026-01-01T00:00:00Z")
                                .param("dateTo", "2026-02-01T00:00:00Z"))
                .andExpect(status().isOk());

        var criteria = ArgumentCaptor.forClass(SearchWorkshopsCriteria.class);
        verify(searchWorkshops).search(criteria.capture());
        var c = criteria.getValue();
        assertThat(c.q()).isEqualTo("kubernetes");
        assertThat(c.speaker()).isEqualTo("Ada");
        assertThat(c.location()).isEqualTo("Room 1");
        assertThat(c.dateFrom()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(c.dateTo()).isEqualTo(java.time.Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/v1/workshops rejects a malformed dateFrom with 400 problem+json")
    void search_rejectsMalformedDate() throws Exception {
        mockMvc.perform(get("/api/v1/workshops").param("dateFrom", "tomorrow"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(searchWorkshops);
    }

    private static WorkshopResult detail(UUID id) {
        var now = java.time.Instant.parse("2026-12-01T09:00:00Z");
        return new WorkshopResult(
                id,
                "T",
                null,
                null,
                "Ada",
                null,
                "Room 1",
                now,
                now.plusSeconds(3600),
                30,
                12,
                false,
                null,
                false,
                "PUBLISHED",
                0,
                now,
                now);
    }

    @Test
    @DisplayName(
            "GET /api/v1/workshops/{id} is public: no token → 200 with an empty permission set")
    void detail_anonymous() throws Exception {
        var id = UUID.randomUUID();
        when(getWorkshop.get(any())).thenReturn(detail(id));

        mockMvc.perform(get("/api/v1/workshops/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speakerName").value("Ada"))
                .andExpect(jsonPath("$.location").value("Room 1"))
                .andExpect(jsonPath("$.seatsTaken").value(12));

        var query = ArgumentCaptor.forClass(GetWorkshopQuery.class);
        verify(getWorkshop).get(query.capture());
        assertThat(query.getValue().workshopId()).isEqualTo(id);
        assertThat(query.getValue().permissions()).isEmpty();
    }

    @Test
    @DisplayName("GET /api/v1/workshops/{id} passes the token's permissions to the use case")
    void detail_withToken() throws Exception {
        var id = UUID.randomUUID();
        when(getWorkshop.get(any())).thenReturn(detail(id));

        mockMvc.perform(
                        get("/api/v1/workshops/{id}", id)
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + TestJwts.valid(
                                                        UUID.randomUUID(), "WORKSHOP_EDIT")))
                .andExpect(status().isOk());

        var query = ArgumentCaptor.forClass(GetWorkshopQuery.class);
        verify(getWorkshop).get(query.capture());
        assertThat(query.getValue().permissions()).containsExactly("WORKSHOP_EDIT");
    }

    @Test
    @DisplayName("GET /api/v1/workshops/{id} maps 'not found' to 404 problem+json")
    void detail_notFound() throws Exception {
        var id = UUID.randomUUID();
        when(getWorkshop.get(any())).thenThrow(new WorkshopNotFoundException(WorkshopId.of(id)));

        mockMvc.perform(get("/api/v1/workshops/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("GET /api/v1/workshops/{id} with a forged token is 401, not silently anonymous")
    void detail_badToken() throws Exception {
        mockMvc.perform(
                        get("/api/v1/workshops/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(getWorkshop);
    }

    @Test
    @DisplayName("GET /api/v1/workshops/{id} rejects a non-UUID id with 400 problem+json")
    void detail_malformedId() throws Exception {
        mockMvc.perform(get("/api/v1/workshops/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
