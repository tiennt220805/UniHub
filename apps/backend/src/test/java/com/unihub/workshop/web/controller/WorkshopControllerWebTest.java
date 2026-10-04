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

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.result.SearchWorkshopsResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// Web slice (backend-coding-standards.md §27 #7): the use case is mocked, no container, no
// database.
// Pins the request-shape rules packages/api-contract/common/pagination.yaml states for
// `page`/`size`.
@WebMvcTest(WorkshopController.class)
class WorkshopControllerWebTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SearchWorkshopsUseCase searchWorkshops;

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
}
