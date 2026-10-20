package com.unihub.workshop.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.command.CancelWorkshopCommand;
import com.unihub.workshop.application.command.CreateWorkshopCommand;
import com.unihub.workshop.application.command.PublishWorkshopCommand;
import com.unihub.workshop.application.command.UpdateWorkshopCommand;
import com.unihub.workshop.application.port.in.CancelWorkshopUseCase;
import com.unihub.workshop.application.port.in.CreateWorkshopUseCase;
import com.unihub.workshop.application.port.in.GetWorkshopUseCase;
import com.unihub.workshop.application.port.in.PublishWorkshopUseCase;
import com.unihub.workshop.application.port.in.SearchWorkshopsUseCase;
import com.unihub.workshop.application.port.in.UpdateWorkshopUseCase;
import com.unihub.workshop.application.result.WorkshopResult;
import com.unihub.workshop.domain.exception.InvalidWorkshopException;
import com.unihub.workshop.domain.exception.InvalidWorkshopStateException;
import com.unihub.workshop.domain.exception.WorkshopNotFoundException;
import com.unihub.workshop.domain.model.WorkshopStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web slice for the Organizer/Admin write endpoints (M-04): authorization is by permission and is
 * decided before the use case is reached; request-shape rules of packages/api-contract are 400 and
 * never reach the use case; domain failures map to 404/409 problem+json
 * (backend-coding-standards.md §10, §17, §18). Use cases are mocked, no container.
 */
@WebMvcTest(WorkshopController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class WorkshopWriteControllerWebTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T11:00:00Z");

    private static final String VALID_CREATE =
            """
            {"title":"Intro to Kubernetes","speakerName":"Ada Lovelace","location":"Room 101",
             "startTime":"2026-12-01T09:00:00Z","endTime":"2026-12-01T11:00:00Z",
             "capacity":50,"isPaid":false}""";

    @Autowired private MockMvc mockMvc;

    @MockBean private SearchWorkshopsUseCase searchWorkshops;
    @MockBean private CreateWorkshopUseCase createWorkshop;
    @MockBean private UpdateWorkshopUseCase updateWorkshop;
    @MockBean private PublishWorkshopUseCase publishWorkshop;
    @MockBean private CancelWorkshopUseCase cancelWorkshop;
    @MockBean private GetWorkshopUseCase getWorkshop;

    private static WorkshopResult result(String status) {
        return new WorkshopResult(
                ID,
                "Intro to Kubernetes",
                null,
                null,
                "Ada Lovelace",
                null,
                "Room 101",
                START,
                END,
                50,
                0,
                false,
                null,
                false,
                status,
                0,
                Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-02T09:30:00Z"));
    }

    private static MockHttpServletRequestBuilder as(
            MockHttpServletRequestBuilder request, String permission, String json) {
        return request.header("Authorization", "Bearer " + TestJwts.valid(USER, permission))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    // ---------- authorization ----------

    @Test
    @DisplayName("POST /workshops without a token is 401 problem+json; use case not reached")
    void create_noToken_401() throws Exception {
        mockMvc.perform(
                        post("/api/v1/workshops")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_CREATE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(createWorkshop);
    }

    @Test
    @DisplayName("POST /workshops needs WORKSHOP_CREATE: WORKSHOP_EDIT alone is 403")
    void create_wrongPermission_403() throws Exception {
        mockMvc.perform(as(post("/api/v1/workshops"), "WORKSHOP_EDIT", VALID_CREATE))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(createWorkshop);
    }

    @Test
    @DisplayName("PATCH /workshops/{id} needs WORKSHOP_EDIT: WORKSHOP_CREATE alone is 403")
    void update_wrongPermission_403() throws Exception {
        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_CREATE", "{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(updateWorkshop);
    }

    @Test
    @DisplayName("POST /workshops/{id}/cancel needs WORKSHOP_EDIT; no token is 401, wrong is 403")
    void cancel_authorization() throws Exception {
        mockMvc.perform(post("/api/v1/workshops/" + ID + "/cancel"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/cancel"), "WORKSHOP_CREATE", ""))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cancelWorkshop);
    }

    @Test
    @DisplayName("POST /workshops/{id}/publish needs WORKSHOP_EDIT; no token is 401, wrong is 403")
    void publish_authorization() throws Exception {
        mockMvc.perform(post("/api/v1/workshops/" + ID + "/publish"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/publish"), "WORKSHOP_CREATE", ""))
                .andExpect(status().isForbidden());

        verifyNoInteractions(publishWorkshop);
    }

    // ---------- create ----------

    @Test
    @DisplayName("POST /workshops with a valid body is 201 and passes the actor and fields on")
    void create_valid_201() throws Exception {
        when(createWorkshop.create(any())).thenReturn(result("DRAFT"));

        mockMvc.perform(as(post("/api/v1/workshops"), "WORKSHOP_CREATE", VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.isPaid").value(false))
                .andExpect(jsonPath("$.hasBrochure").value(false))
                .andExpect(jsonPath("$.seatsTaken").value(0))
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.createdAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-02T09:30:00Z"));

        var command = ArgumentCaptor.forClass(CreateWorkshopCommand.class);
        verify(createWorkshop).create(command.capture());
        var c = command.getValue();
        assertThat(c.title()).isEqualTo("Intro to Kubernetes");
        assertThat(c.startTime()).isEqualTo(START);
        assertThat(c.endTime()).isEqualTo(END);
        assertThat(c.capacity()).isEqualTo(50);
        assertThat(c.paid()).isFalse();
        assertThat(c.price()).isNull();
        assertThat(c.actor()).hasFieldOrProperty("userId");
    }

    @Test
    @DisplayName("POST /workshops with a paid workshop and price passes the price on exactly")
    void create_paid_passesPrice() throws Exception {
        when(createWorkshop.create(any())).thenReturn(result("DRAFT"));
        var body = VALID_CREATE.replace("\"isPaid\":false", "\"isPaid\":true,\"price\":150000.50");

        mockMvc.perform(as(post("/api/v1/workshops"), "WORKSHOP_CREATE", body))
                .andExpect(status().isCreated());

        var command = ArgumentCaptor.forClass(CreateWorkshopCommand.class);
        verify(createWorkshop).create(command.capture());
        assertThat(command.getValue().paid()).isTrue();
        assertThat(command.getValue().price()).isEqualByComparingTo(new BigDecimal("150000.50"));
    }

    @Test
    @DisplayName("POST /workshops accepts title, speakerName and location of exactly 255 chars")
    void create_textAtColumnLimit_201() throws Exception {
        when(createWorkshop.create(any())).thenReturn(result("DRAFT"));
        var body =
                VALID_CREATE
                        .replace("Intro to Kubernetes", "t".repeat(255))
                        .replace("Ada Lovelace", "s".repeat(255))
                        .replace("Room 101", "l".repeat(255));

        mockMvc.perform(as(post("/api/v1/workshops"), "WORKSHOP_CREATE", body))
                .andExpect(status().isCreated());
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of(
                        "title missing",
                        VALID_CREATE.replace("\"title\":\"Intro to Kubernetes\",", "")),
                Arguments.of("title blank", VALID_CREATE.replace("Intro to Kubernetes", "   ")),
                Arguments.of(
                        "title over 255",
                        VALID_CREATE.replace("Intro to Kubernetes", "t".repeat(256))),
                Arguments.of(
                        "speakerName over 255",
                        VALID_CREATE.replace("Ada Lovelace", "s".repeat(256))),
                Arguments.of(
                        "location over 255", VALID_CREATE.replace("Room 101", "l".repeat(256))),
                Arguments.of(
                        "capacity negative",
                        VALID_CREATE.replace("\"capacity\":50", "\"capacity\":-1")),
                Arguments.of("capacity missing", VALID_CREATE.replace("\"capacity\":50,", "")),
                Arguments.of("isPaid missing", VALID_CREATE.replace(",\"isPaid\":false", "")),
                Arguments.of(
                        "endTime equals startTime",
                        VALID_CREATE.replace("2026-12-01T11:00:00Z", "2026-12-01T09:00:00Z")),
                Arguments.of(
                        "endTime before startTime",
                        VALID_CREATE.replace("2026-12-01T11:00:00Z", "2026-12-01T08:00:00Z")),
                Arguments.of(
                        "startTime missing",
                        VALID_CREATE.replace("\"startTime\":\"2026-12-01T09:00:00Z\",", "")),
                Arguments.of(
                        "paid without price",
                        VALID_CREATE.replace("\"isPaid\":false", "\"isPaid\":true")),
                Arguments.of(
                        "paid with zero price",
                        VALID_CREATE.replace("\"isPaid\":false", "\"isPaid\":true,\"price\":0")),
                Arguments.of(
                        "free with a price",
                        VALID_CREATE.replace("\"isPaid\":false", "\"isPaid\":false,\"price\":10")),
                Arguments.of("not json", "{"),
                Arguments.of(
                        "startTime not a date-time",
                        VALID_CREATE.replace("2026-12-01T09:00:00Z", "tomorrow")));
    }

    @ParameterizedTest(name = "{0} → 400 problem+json, use case not reached")
    @MethodSource("invalidCreateBodies")
    @DisplayName("POST /workshops rejects a request that breaks the contract's shape rules")
    void create_invalid_400(String why, String body) throws Exception {
        mockMvc.perform(as(post("/api/v1/workshops"), "WORKSHOP_CREATE", body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/workshops"));

        verifyNoInteractions(createWorkshop);
    }

    // ---------- update / reschedule ----------

    @Test
    @DisplayName("PATCH with only some fields passes only those on; the rest are unchanged")
    void update_presence() throws Exception {
        when(updateWorkshop.update(any())).thenReturn(result("PUBLISHED"));

        mockMvc.perform(
                        as(
                                patch("/api/v1/workshops/" + ID),
                                "WORKSHOP_EDIT",
                                "{\"title\":\"Renamed\",\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        var command = ArgumentCaptor.forClass(UpdateWorkshopCommand.class);
        verify(updateWorkshop).update(command.capture());
        var c = command.getValue();
        assertThat(c.workshopId()).isEqualTo(ID);
        assertThat(c.title().present()).isTrue();
        assertThat(c.title().value()).isEqualTo("Renamed");
        assertThat(c.description().present()).isTrue(); // explicit null = clear
        assertThat(c.description().value()).isNull();
        assertThat(c.speakerBio().present()).isFalse(); // absent = leave alone
        assertThat(c.location().present()).isFalse();
        assertThat(c.startTime().present()).isFalse();
        assertThat(c.capacity().present()).isFalse();
        assertThat(c.price().present()).isFalse();
    }

    @Test
    @DisplayName("PATCH with startTime and endTime is a reschedule and carries both times")
    void update_reschedule() throws Exception {
        when(updateWorkshop.update(any())).thenReturn(result("PUBLISHED"));

        mockMvc.perform(
                        as(
                                patch("/api/v1/workshops/" + ID),
                                "WORKSHOP_EDIT",
                                "{\"startTime\":\"2026-12-02T09:00:00Z\",\"endTime\":\"2026-12-02T11:00:00Z\"}"))
                .andExpect(status().isOk());

        var command = ArgumentCaptor.forClass(UpdateWorkshopCommand.class);
        verify(updateWorkshop).update(command.capture());
        assertThat(command.getValue().startTime().value())
                .isEqualTo(Instant.parse("2026-12-02T09:00:00Z"));
        assertThat(command.getValue().endTime().value())
                .isEqualTo(Instant.parse("2026-12-02T11:00:00Z"));
        assertThat(command.getValue().title().present()).isFalse();
    }

    @Test
    @DisplayName("PATCH with an empty body is valid and changes nothing")
    void update_emptyBody_ok() throws Exception {
        when(updateWorkshop.update(any())).thenReturn(result("DRAFT"));

        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_EDIT", "{}"))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> invalidPatchBodies() {
        return Stream.of(
                Arguments.of("title null", "{\"title\":null}"),
                Arguments.of("title blank", "{\"title\":\"  \"}"),
                Arguments.of("title over 255", "{\"title\":\"" + "t".repeat(256) + "\"}"),
                Arguments.of("speakerName null", "{\"speakerName\":null}"),
                Arguments.of("location null", "{\"location\":null}"),
                Arguments.of("capacity negative", "{\"capacity\":-1}"),
                Arguments.of("capacity null", "{\"capacity\":null}"),
                Arguments.of("startTime null", "{\"startTime\":null}"),
                Arguments.of(
                        "both times, end not after start",
                        "{\"startTime\":\"2026-12-02T11:00:00Z\",\"endTime\":\"2026-12-02T09:00:00Z\"}"),
                Arguments.of("isPaid is not editable", "{\"isPaid\":true}"),
                Arguments.of("seatsTaken is not editable", "{\"seatsTaken\":99}"),
                Arguments.of("status is not editable", "{\"status\":\"PUBLISHED\"}"),
                Arguments.of("price negative", "{\"price\":-5}"),
                Arguments.of("price zero", "{\"price\":0}"),
                Arguments.of("not json", "["));
    }

    @ParameterizedTest(name = "{0} → 400 problem+json, use case not reached")
    @MethodSource("invalidPatchBodies")
    @DisplayName("PATCH /workshops/{id} rejects a request that breaks the contract's shape rules")
    void update_invalid_400(String why, String body) throws Exception {
        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_EDIT", body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(updateWorkshop);
    }

    @Test
    @DisplayName("PATCH with a non-UUID id is 400")
    void update_badId_400() throws Exception {
        mockMvc.perform(as(patch("/api/v1/workshops/not-a-uuid"), "WORKSHOP_EDIT", "{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(updateWorkshop);
    }

    // ---------- publish ----------

    @Test
    @DisplayName("POST /workshops/{id}/publish is 200 with the published workshop")
    void publish_200() throws Exception {
        when(publishWorkshop.publish(any())).thenReturn(result("PUBLISHED"));

        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/publish"), "WORKSHOP_EDIT", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        var command = ArgumentCaptor.forClass(PublishWorkshopCommand.class);
        verify(publishWorkshop).publish(command.capture());
        assertThat(command.getValue().workshopId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("publishing a workshop that is not a DRAFT is 409 problem+json")
    void publish_notDraft_409() throws Exception {
        when(publishWorkshop.publish(any()))
                .thenThrow(
                        new InvalidWorkshopStateException(
                                WorkshopId.of(ID), WorkshopStatus.PUBLISHED, "publish"));

        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/publish"), "WORKSHOP_EDIT", ""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ---------- cancel ----------

    @Test
    @DisplayName("POST /workshops/{id}/cancel is 200 with the cancelled workshop")
    void cancel_200() throws Exception {
        when(cancelWorkshop.cancel(any())).thenReturn(result("CANCELLED"));

        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/cancel"), "WORKSHOP_EDIT", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        var command = ArgumentCaptor.forClass(CancelWorkshopCommand.class);
        verify(cancelWorkshop).cancel(command.capture());
        assertThat(command.getValue().workshopId()).isEqualTo(ID);
    }

    // ---------- domain failures → problem+json ----------

    @Test
    @DisplayName("an unknown workshop is 404 problem+json")
    void notFound_404() throws Exception {
        when(cancelWorkshop.cancel(any()))
                .thenThrow(new WorkshopNotFoundException(WorkshopId.of(ID)));

        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/cancel"), "WORKSHOP_EDIT", ""))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/workshops/" + ID + "/cancel"));
    }

    @Test
    @DisplayName("cancelling a terminal workshop is 409 problem+json")
    void terminalState_409() throws Exception {
        when(cancelWorkshop.cancel(any()))
                .thenThrow(
                        new InvalidWorkshopStateException(
                                WorkshopId.of(ID), WorkshopStatus.COMPLETED, "cancel"));

        mockMvc.perform(as(post("/api/v1/workshops/" + ID + "/cancel"), "WORKSHOP_EDIT", ""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    @DisplayName("editing a terminal workshop is 409 problem+json")
    void updateTerminal_409() throws Exception {
        when(updateWorkshop.update(any()))
                .thenThrow(
                        new InvalidWorkshopStateException(
                                WorkshopId.of(ID), WorkshopStatus.CANCELLED, "edit"));

        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_EDIT", "{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a lost version race is 409 problem+json")
    void lostRace_409() throws Exception {
        when(updateWorkshop.update(any()))
                .thenThrow(new OptimisticConflictException("WORKSHOP", ID));

        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_EDIT", "{}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("a stateful rule the shape cannot catch (price on a free workshop) is 409")
    void statefulRule_409() throws Exception {
        when(updateWorkshop.update(any()))
                .thenThrow(new InvalidWorkshopException("a free workshop must have no price"));

        mockMvc.perform(as(patch("/api/v1/workshops/" + ID), "WORKSHOP_EDIT", "{\"price\":10}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
