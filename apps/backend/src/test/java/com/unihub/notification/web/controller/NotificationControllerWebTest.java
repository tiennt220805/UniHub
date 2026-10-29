package com.unihub.notification.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.notification.application.port.in.ListMyNotificationsUseCase;
import com.unihub.notification.application.query.ListMyNotificationsQuery;
import com.unihub.notification.application.result.MyNotificationsResult;
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
 * Web slice for {@code listMyNotifications} (Phase 4 checklist D38, AC38.1): any authenticated
 * caller may ask, no permission code; the caller always comes from the token, never from a
 * parameter; the bounds are 400 and never reach the use case.
 */
@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class NotificationControllerWebTest {
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID NOTIFICATION = UUID.randomUUID();
    private static final UUID WORKSHOP = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-12-01T09:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockBean private ListMyNotificationsUseCase listMyNotifications;

    private static String token() {
        // no permission codes at all: the endpoint needs only an authenticated caller
        return "Bearer " + TestJwts.valid(STUDENT);
    }

    private static MyNotificationsResult.Item sent() {
        return new MyNotificationsResult.Item(
                NOTIFICATION,
                "WORKSHOP_CANCELLED",
                "Workshop đã bị hủy: Intro",
                "Workshop \"Intro\" đã bị hủy.",
                "WORKSHOP",
                WORKSHOP,
                "SENT",
                AT.plusSeconds(5),
                AT);
    }

    @Test
    @DisplayName("200 with the stored title and body, the page envelope, and no read/unread field")
    void list_is200() throws Exception {
        when(listMyNotifications.list(any()))
                .thenReturn(new MyNotificationsResult(List.of(sent()), 1, 10, 21, 3));

        mockMvc.perform(
                        get("/api/v1/notifications?page=1&size=10")
                                .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(NOTIFICATION.toString()))
                .andExpect(jsonPath("$.items[0].type").value("WORKSHOP_CANCELLED"))
                .andExpect(jsonPath("$.items[0].title").value("Workshop đã bị hủy: Intro"))
                .andExpect(jsonPath("$.items[0].body").value("Workshop \"Intro\" đã bị hủy."))
                .andExpect(jsonPath("$.items[0].relatedEntityType").value("WORKSHOP"))
                .andExpect(jsonPath("$.items[0].relatedEntityId").value(WORKSHOP.toString()))
                .andExpect(jsonPath("$.items[0].status").value("SENT"))
                .andExpect(jsonPath("$.items[0].sentAt").value("2026-12-01T09:00:05Z"))
                .andExpect(jsonPath("$.items[0].read").doesNotExist())
                .andExpect(jsonPath("$.page.page").value(1))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(21))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    @DisplayName(
            "the use case is asked as the token's subject, with the status filter and the page")
    void list_isScopedToTheCaller() throws Exception {
        when(listMyNotifications.list(any()))
                .thenReturn(new MyNotificationsResult(List.of(), 2, 5, 0, 0));

        mockMvc.perform(
                        get("/api/v1/notifications?page=2&size=5&status=SENT")
                                .header("Authorization", token()))
                .andExpect(status().isOk());

        var query = ArgumentCaptor.forClass(ListMyNotificationsQuery.class);
        verify(listMyNotifications).list(query.capture());
        assertThat(query.getValue().caller().value()).isEqualTo(STUDENT);
        assertThat(query.getValue().status()).isEqualTo("SENT");
        assertThat(query.getValue().page()).isEqualTo(2);
        assertThat(query.getValue().size()).isEqualTo(5);
    }

    @Test
    @DisplayName(
            "a user id in the query is ignored: there is no way to read another user's inbox (AC38.2)")
    void list_cannotNameAnotherUser() throws Exception {
        when(listMyNotifications.list(any()))
                .thenReturn(new MyNotificationsResult(List.of(), 0, 20, 0, 0));
        var someoneElse = UUID.randomUUID();

        mockMvc.perform(
                        get("/api/v1/notifications?userId=" + someoneElse)
                                .header("Authorization", token()))
                .andExpect(status().isOk());

        var query = ArgumentCaptor.forClass(ListMyNotificationsQuery.class);
        verify(listMyNotifications).list(query.capture());
        assertThat(query.getValue().caller().value()).isEqualTo(STUDENT);
    }

    @Test
    @DisplayName("without parameters the list defaults to page 0, size 20, any status")
    void list_defaults() throws Exception {
        when(listMyNotifications.list(any()))
                .thenReturn(new MyNotificationsResult(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", token()))
                .andExpect(status().isOk());

        var query = ArgumentCaptor.forClass(ListMyNotificationsQuery.class);
        verify(listMyNotifications).list(query.capture());
        assertThat(query.getValue().status()).isNull();
        assertThat(query.getValue().page()).isZero();
        assertThat(query.getValue().size()).isEqualTo(20);
    }

    @Test
    @DisplayName("without a token it is 401 and reaches nothing")
    void unauthenticated_is401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());

        verifyNoInteractions(listMyNotifications);
    }

    @Test
    @DisplayName(
            "page below 0, size outside 1..100 and an unknown status are 400, before the use case")
    void list_badParameters_are400() throws Exception {
        for (var query : new String[] {"page=-1", "size=0", "size=101", "status=READ"}) {
            mockMvc.perform(get("/api/v1/notifications?" + query).header("Authorization", token()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(listMyNotifications);
    }
}
