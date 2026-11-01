package com.unihub.checkin.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.checkin.application.port.in.GetWorkshopReportUseCase;
import com.unihub.checkin.application.port.in.ListWorkshopAttendancesUseCase;
import com.unihub.checkin.application.query.ListWorkshopAttendancesQuery;
import com.unihub.checkin.application.result.WorkshopAttendancesResult;
import com.unihub.checkin.application.result.WorkshopReportResult;
import com.unihub.checkin.domain.exception.ReportedWorkshopNotFoundException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Web slice for {@code getWorkshopReport} and {@code listWorkshopAttendances} (Phase 5 checklist
 * tasks 5.1, 5.2; D46): the permission, the paging bounds, the shape, and the mapping of "workshop
 * not found". The figures are the use cases', tested apart.
 */
@WebMvcTest(WorkshopReportController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class WorkshopReportControllerWebTest {
    private static final UUID USER = UUID.randomUUID();
    private static final String WORKSHOP_ID = "6b1f0c3e-2a44-4d8e-9a53-1f0a7c5d9e21";
    private static final UUID WORKSHOP = UUID.fromString(WORKSHOP_ID);
    private static final String REPORT = "/api/v1/workshops/" + WORKSHOP_ID + "/report";
    private static final String LIST = "/api/v1/workshops/" + WORKSHOP_ID + "/attendances";

    @Autowired private MockMvc mockMvc;

    @MockBean private GetWorkshopReportUseCase getReport;
    @MockBean private ListWorkshopAttendancesUseCase listAttendances;

    private static String viewer() {
        return "Bearer " + TestJwts.valid(USER, "REPORT_VIEW");
    }

    private ResultActions send(String url, String token) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mockMvc.perform(request);
    }

    private static WorkshopAttendancesResult page(int page, int size) {
        return new WorkshopAttendancesResult(
                List.of(new WorkshopAttendancesResult.Item(UUID.randomUUID(), Instant.EPOCH)),
                page,
                size,
                1,
                1);
    }

    // ---------- permission ----------

    @ParameterizedTest
    @ValueSource(strings = {REPORT, LIST})
    @DisplayName("no token is 401 and the use case is never reached")
    void noToken_is401(String url) throws Exception {
        send(url, null).andExpect(status().isUnauthorized());

        verifyNoInteractions(getReport, listAttendances);
    }

    @ParameterizedTest
    @ValueSource(strings = {REPORT, LIST})
    @DisplayName("a token without REPORT_VIEW is 403 and the use case is never reached")
    void withoutPermission_is403(String url) throws Exception {
        send(url, "Bearer " + TestJwts.valid(USER, "CHECKIN_SCAN", "REGISTRATION_CREATE"))
                .andExpect(status().isForbidden());
        send(url, "Bearer " + TestJwts.valid(USER)).andExpect(status().isForbidden());

        verifyNoInteractions(getReport, listAttendances);
    }

    // ---------- the report ----------

    @Test
    @DisplayName("the report carries the figures, and a rate that is null when it is undefined")
    void report_shape() throws Exception {
        when(getReport.report(WorkshopId.of(WORKSHOP)))
                .thenReturn(new WorkshopReportResult(WORKSHOP, 40, 4, 3, 1, 2, 2.0 / 3.0));

        send(REPORT, viewer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workshopId").value(WORKSHOP.toString()))
                .andExpect(jsonPath("$.capacity").value(40))
                .andExpect(jsonPath("$.seatsTaken").value(4))
                .andExpect(jsonPath("$.confirmedCount").value(3))
                .andExpect(jsonPath("$.reservedCount").value(1))
                .andExpect(jsonPath("$.checkedInCount").value(2))
                .andExpect(jsonPath("$.checkInRate").value(2.0 / 3.0));

        when(getReport.report(WorkshopId.of(WORKSHOP)))
                .thenReturn(new WorkshopReportResult(WORKSHOP, 40, 0, 0, 0, 0, null));

        send(REPORT, viewer())
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "\"checkInRate\":null")));
    }

    @Test
    @DisplayName("a malformed workshop id is 400 and the use case is never reached")
    void report_malformedId_is400() throws Exception {
        send("/api/v1/workshops/not-a-uuid/report", viewer()).andExpect(status().isBadRequest());
        send("/api/v1/workshops/not-a-uuid/attendances", viewer())
                .andExpect(status().isBadRequest());

        verifyNoInteractions(getReport, listAttendances);
    }

    @ParameterizedTest
    @ValueSource(strings = {REPORT, LIST})
    @DisplayName("an unknown workshop is a 404 problem")
    void unknownWorkshop_is404(String url) throws Exception {
        var unknown = new ReportedWorkshopNotFoundException(WorkshopId.of(WORKSHOP));
        when(getReport.report(any())).thenThrow(unknown);
        when(listAttendances.list(any())).thenThrow(unknown);

        send(url, viewer())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ---------- the list: paging bounds (BVA) ----------

    @Test
    @DisplayName("defaults are page 0, size 20")
    void list_defaults() throws Exception {
        when(listAttendances.list(any())).thenReturn(page(0, 20));

        send(LIST, viewer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.page").value(0))
                .andExpect(jsonPath("$.page.size").value(20));

        var query = ArgumentCaptor.forClass(ListWorkshopAttendancesQuery.class);
        verify(listAttendances).list(query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue())
                .isEqualTo(new ListWorkshopAttendancesQuery(WorkshopId.of(WORKSHOP), 0, 20));
    }

    @Test
    @DisplayName("size 1 and size 100 are 200")
    void list_sizeBoundsAreInclusive() throws Exception {
        when(listAttendances.list(any())).thenReturn(page(0, 1));

        send(LIST + "?size=1", viewer()).andExpect(status().isOk());
        send(LIST + "?size=100", viewer()).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=-5", "page=x", "size=x"})
    @DisplayName("a page below 0, a size outside 1..100 and a non-number are 400")
    void list_outOfBounds_is400(String parameter) throws Exception {
        send(LIST + "?" + parameter, viewer()).andExpect(status().isBadRequest());

        verifyNoInteractions(listAttendances);
    }

    @Test
    @DisplayName("a page far past the last is passed on, and an empty page is 200")
    void list_pagePastTheEnd_isAnEmptyPage() throws Exception {
        when(listAttendances.list(any()))
                .thenReturn(new WorkshopAttendancesResult(List.of(), 99, 20, 1, 1));

        send(LIST + "?page=99", viewer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }
}
