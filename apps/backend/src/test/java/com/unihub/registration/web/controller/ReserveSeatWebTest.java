package com.unihub.registration.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.registration.domain.exception.SeatLockUnavailableException;
import com.unihub.registration.domain.exception.SeatTemporarilyUnavailableException;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Web slice for {@code createRegistration}: authorization is by permission and decided before the
 * use case; the request shape is 400 and never reaches it; each Result variant and each failure
 * maps to its contract status, problem {@code type} and headers (backend-coding-standards.md §10,
 * §17). The use case is mocked, no container.
 */
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class ReserveSeatWebTest {

    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID WORKSHOP = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant HOLD_UNTIL = Instant.parse("2026-12-01T09:15:00Z");
    private static final String BODY = "{\"workshopId\":\"" + WORKSHOP + "\"}";
    private static final String SOLD_OUT = "urn:unihub:problem:sold-out";

    @Autowired private MockMvc mockMvc;

    @MockBean private ReserveSeatUseCase reserveSeat;

    private static MockHttpServletRequestBuilder request(String token, String key, String json) {
        var builder = post("/api/v1/registrations").contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.content(json);
    }

    private static String student() {
        return TestJwts.valid(STUDENT, "REGISTRATION_CREATE");
    }

    private MockHttpServletRequestBuilder valid() {
        return request(student(), KEY.toString(), BODY);
    }

    // ---------- outcomes ----------

    @Test
    @DisplayName("a free registration is 201 CONFIRMED, FREE, with the caller as user and no hold")
    void confirmed_is201() throws Exception {
        var id = RegistrationId.newId();
        when(reserveSeat.reserve(any()))
                .thenReturn(new ReserveSeatResult.Confirmed(id, CREATED, CREATED));

        mockMvc.perform(valid())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.workshopId").value(WORKSHOP.toString()))
                .andExpect(jsonPath("$.userId").value(STUDENT.toString()))
                .andExpect(jsonPath("$.ticketType").value("FREE"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.holdExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.ticketCode").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-12-01T09:00:00Z"));

        var command = ArgumentCaptor.forClass(ReserveSeatCommand.class);
        verify(reserveSeat).reserve(command.capture());
        assertThat(command.getValue().workshopId()).isEqualTo(WorkshopId.of(WORKSHOP));
        assertThat(command.getValue().actor().userId().value()).isEqualTo(STUDENT);
    }

    @Test
    @DisplayName("a paid registration is 201 RESERVED, PAID, with the hold deadline")
    void reserved_is201() throws Exception {
        when(reserveSeat.reserve(any()))
                .thenReturn(
                        new ReserveSeatResult.Reserved(
                                RegistrationId.newId(), HOLD_UNTIL, CREATED, CREATED));

        mockMvc.perform(valid())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketType").value("PAID"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.holdExpiresAt").value("2026-12-01T09:15:00Z"));
    }

    @Test
    @DisplayName("sold out is 409 problem+json of type sold-out")
    void soldOut_is409() throws Exception {
        when(reserveSeat.reserve(any())).thenReturn(new ReserveSeatResult.SoldOut());

        mockMvc.perform(valid())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(SOLD_OUT))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value("/api/v1/registrations"));
    }

    @Test
    @DisplayName("an existing active registration is 409 of type already-registered")
    void alreadyRegistered_is409() throws Exception {
        when(reserveSeat.reserve(any())).thenReturn(new ReserveSeatResult.AlreadyRegistered());

        mockMvc.perform(valid())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:unihub:problem:already-registered"));
    }

    @Test
    @DisplayName("a workshop that is not open is 409 of type registration-not-open")
    void notOpen_is409() throws Exception {
        when(reserveSeat.reserve(any())).thenReturn(new ReserveSeatResult.NotOpenForRegistration());

        mockMvc.perform(valid())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:unihub:problem:registration-not-open"));
    }

    // ---------- failures that are exceptions ----------

    @Test
    @DisplayName("a seat lock that cannot be taken is 503 with Retry-After, type about:blank")
    void lockUnavailable_is503_withRetryAfter() throws Exception {
        when(reserveSeat.reserve(any()))
                .thenThrow(new SeatLockUnavailableException(WorkshopId.of(WORKSHOP)));

        mockMvc.perform(valid())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"));
    }

    @Test
    @DisplayName("seat-claim retries exhausted is 503 with Retry-After, never 409")
    void retriesExhausted_is503_withRetryAfter() throws Exception {
        when(reserveSeat.reserve(any()))
                .thenThrow(
                        new SeatTemporarilyUnavailableException(
                                WorkshopId.of(WORKSHOP),
                                new OptimisticConflictException("WORKSHOP", WORKSHOP)));

        mockMvc.perform(valid())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    // ---------- request shape: 400, use case never reached ----------

    @Test
    @DisplayName("a missing Idempotency-Key is 400")
    void missingKey_is400() throws Exception {
        mockMvc.perform(request(student(), null, BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(reserveSeat);
    }

    @Test
    @DisplayName("an Idempotency-Key that is not a UUID is 400")
    void malformedKey_is400() throws Exception {
        mockMvc.perform(request(student(), "not-a-uuid", BODY)).andExpect(status().isBadRequest());

        verifyNoInteractions(reserveSeat);
    }

    @Test
    @DisplayName("a missing workshopId is 400")
    void missingWorkshopId_is400() throws Exception {
        mockMvc.perform(request(student(), KEY.toString(), "{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reserveSeat);
    }

    @Test
    @DisplayName("a workshopId that is not a UUID is 400")
    void malformedWorkshopId_is400() throws Exception {
        mockMvc.perform(request(student(), KEY.toString(), "{\"workshopId\":\"nope\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reserveSeat);
    }

    // ---------- authorization ----------

    @Test
    @DisplayName("no token is 401 problem+json; the use case is not reached")
    void noToken_is401() throws Exception {
        mockMvc.perform(request(null, KEY.toString(), BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(reserveSeat);
    }

    @Test
    @DisplayName("a token without REGISTRATION_CREATE is 403, even with other permissions")
    void withoutRegistrationCreate_is403() throws Exception {
        var organizer = TestJwts.valid(STUDENT, "WORKSHOP_CREATE", "WORKSHOP_EDIT");

        mockMvc.perform(request(organizer, KEY.toString(), BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(reserveSeat);
    }
}
