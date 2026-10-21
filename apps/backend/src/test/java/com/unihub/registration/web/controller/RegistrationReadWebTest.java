package com.unihub.registration.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.registration.application.port.in.GetRegistrationUseCase;
import com.unihub.registration.application.port.in.ListMyRegistrationsUseCase;
import com.unihub.registration.application.port.in.ReserveSeatUseCase;
import com.unihub.registration.application.query.GetRegistrationQuery;
import com.unihub.registration.application.query.ListMyRegistrationsQuery;
import com.unihub.registration.application.result.MyRegistrationsResult;
import com.unihub.registration.application.result.RegistrationResult;
import com.unihub.registration.domain.exception.RegistrationNotFoundException;
import com.unihub.registration.domain.exception.RegistrationNotOwnedException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice for {@code getRegistration} and {@code listMyRegistrations}: any authenticated caller
 * may ask (ownership, not a permission code, is the rule, decided by the use case); the caller
 * always comes from the token; the list's bounds are 400 and never reach the use case.
 */
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class RegistrationReadWebTest {
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID REGISTRATION = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private ReserveSeatUseCase reserveSeat;
    @MockBean private GetRegistrationUseCase getRegistration;
    @MockBean private ListMyRegistrationsUseCase listMyRegistrations;

    private static String token() {
        return "Bearer " + TestJwts.valid(STUDENT);
    }

    private static RegistrationResult reserved() {
        return new RegistrationResult(
                REGISTRATION,
                UUID.randomUUID(),
                STUDENT,
                "PAID",
                "RESERVED",
                AT.plusSeconds(900),
                null,
                null,
                AT,
                AT);
    }

    @Test
    @DisplayName("getRegistration is 200 with the owner's view, asked as the token's subject")
    void get_is200() throws Exception {
        when(getRegistration.get(any())).thenReturn(reserved());

        mockMvc.perform(
                        get("/api/v1/registrations/" + REGISTRATION)
                                .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REGISTRATION.toString()))
                .andExpect(jsonPath("$.ticketType").value("PAID"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.holdExpiresAt").value("2026-12-01T09:15:00Z"))
                .andExpect(jsonPath("$.qrPayload").value((Object) null));

        var query = ArgumentCaptor.forClass(GetRegistrationQuery.class);
        verify(getRegistration).get(query.capture());
        assertThat(query.getValue().caller().value()).isEqualTo(STUDENT);
        assertThat(query.getValue().registrationId()).isEqualTo(RegistrationId.of(REGISTRATION));
    }

    @Test
    @DisplayName("a registration that is not the caller's is 403, an unknown one 404")
    void get_failures() throws Exception {
        // doThrow, not when(): re-stubbing a mock that already throws would throw inside when().
        doThrow(new RegistrationNotOwnedException(RegistrationId.of(REGISTRATION)))
                .when(getRegistration)
                .get(any());
        mockMvc.perform(
                        get("/api/v1/registrations/" + REGISTRATION)
                                .header("Authorization", token()))
                .andExpect(status().isForbidden());

        doThrow(new RegistrationNotFoundException(RegistrationId.of(REGISTRATION)))
                .when(getRegistration)
                .get(any());
        mockMvc.perform(
                        get("/api/v1/registrations/" + REGISTRATION)
                                .header("Authorization", token()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a token both reads are 401 and reach nothing")
    void unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/" + REGISTRATION))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/registrations")).andExpect(status().isUnauthorized());

        verifyNoInteractions(getRegistration, listMyRegistrations);
    }

    @Test
    @DisplayName("an id that is not a UUID is 400")
    void badId_is400() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/nope").header("Authorization", token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the list is 200 with items and the page envelope, scoped to the caller")
    void list_is200() throws Exception {
        when(listMyRegistrations.list(any()))
                .thenReturn(
                        new MyRegistrationsResult(
                                List.of(
                                        new MyRegistrationsResult.Item(
                                                REGISTRATION,
                                                UUID.randomUUID(),
                                                STUDENT,
                                                "FREE",
                                                "CONFIRMED",
                                                AT)),
                                1,
                                10,
                                21,
                                3));

        mockMvc.perform(
                        get("/api/v1/registrations?page=1&size=10&status=CONFIRMED")
                                .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(REGISTRATION.toString()))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].qrPayload").doesNotExist())
                .andExpect(jsonPath("$.page.page").value(1))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(21))
                .andExpect(jsonPath("$.page.totalPages").value(3));

        var query = ArgumentCaptor.forClass(ListMyRegistrationsQuery.class);
        verify(listMyRegistrations).list(query.capture());
        assertThat(query.getValue().caller().value()).isEqualTo(STUDENT);
        assertThat(query.getValue().status()).isEqualTo("CONFIRMED");
        assertThat(query.getValue().page()).isEqualTo(1);
        assertThat(query.getValue().size()).isEqualTo(10);
    }

    @Test
    @DisplayName("without parameters the list defaults to page 0, size 20, any status")
    void list_defaults() throws Exception {
        when(listMyRegistrations.list(any()))
                .thenReturn(new MyRegistrationsResult(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/registrations").header("Authorization", token()))
                .andExpect(status().isOk());

        var query = ArgumentCaptor.forClass(ListMyRegistrationsQuery.class);
        verify(listMyRegistrations).list(query.capture());
        assertThat(query.getValue().status()).isNull();
        assertThat(query.getValue().page()).isZero();
        assertThat(query.getValue().size()).isEqualTo(20);
    }

    @Test
    @DisplayName(
            "page below 0, size outside 1..100 and an unknown status are 400, before the use case")
    void list_badParameters_are400() throws Exception {
        for (var query : new String[] {"page=-1", "size=0", "size=101", "status=PENDING"}) {
            mockMvc.perform(get("/api/v1/registrations?" + query).header("Authorization", token()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(listMyRegistrations);
    }
}
