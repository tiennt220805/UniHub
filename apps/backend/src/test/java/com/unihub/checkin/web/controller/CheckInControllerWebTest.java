package com.unihub.checkin.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unihub.checkin.application.command.SyncScanBatchCommand;
import com.unihub.checkin.application.port.in.SyncScanBatchUseCase;
import com.unihub.checkin.application.result.ScanResult;
import com.unihub.checkin.application.result.SyncScanBatchResult;
import com.unihub.checkin.domain.model.DeviceVerdict;
import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.infrastructure.security.SecurityConfig;
import com.unihub.shared.security.TestJwts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice for {@code syncScanBatch} (Phase 5 checklist task 3.5; D50, D53, D54): the batch
 * bounds, which scans fail the batch and which never do, the caller always from the token, and the
 * permission. The business behaviour is the use case's, tested apart.
 */
@WebMvcTest(CheckInController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "unihub.security.jwt.secret=" + TestJwts.SECRET)
class CheckInControllerWebTest {
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();
    private static final String URL = "/api/v1/checkin/sync";

    @Autowired private MockMvc mockMvc;

    @MockBean private SyncScanBatchUseCase syncScanBatch;

    @BeforeEach
    void echoOutcomes() {
        when(syncScanBatch.sync(any()))
                .thenAnswer(
                        call -> {
                            SyncScanBatchCommand command = call.getArgument(0);
                            return new SyncScanBatchResult(
                                    command.scans().stream()
                                            .map(s -> new ScanResult(s.id(), ScanOutcome.ACCEPTED))
                                            .collect(Collectors.toList()));
                        });
    }

    private static String staffToken() {
        return "Bearer " + TestJwts.valid(STAFF, "CHECKIN_SCAN");
    }

    private static String verified(UUID id, Object ticketCode) {
        return scan(id, "VERIFIED", ticketCode);
    }

    private static String scan(UUID id, String verdict, Object ticketCode) {
        return "{\"id\":\"%s\",\"deviceVerdict\":\"%s\",\"ticketCode\":%s,\"workshopId\":\"%s\","
                        .formatted(id, verdict, jsonOf(ticketCode), ROOM)
                + "\"scannedAt\":\"2026-12-01T09:00:00Z\",\"deviceId\":\"device-1\"}";
    }

    private static String jsonOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return value.toString();
    }

    private static String batch(String... scans) {
        return "{\"scans\":[" + String.join(",", scans) + "]}";
    }

    private static String batchOf(int size) {
        var scans = new ArrayList<String>();
        for (var i = 0; i < size; i++) {
            scans.add(verified(UUID.randomUUID(), "CODE" + i));
        }
        return batch(scans.toArray(String[]::new));
    }

    private org.springframework.test.web.servlet.ResultActions send(String token, String body)
            throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mockMvc.perform(request);
    }

    // ---------- permission ----------

    @Test
    @DisplayName("no token is 401 and the use case is never reached")
    void noToken_is401() throws Exception {
        send(null, batchOf(1)).andExpect(status().isUnauthorized());

        verifyNoInteractions(syncScanBatch);
    }

    @Test
    @DisplayName("a token without CHECKIN_SCAN, a student's for instance, is 403")
    void studentToken_is403() throws Exception {
        send("Bearer " + TestJwts.valid(UUID.randomUUID()), batchOf(1))
                .andExpect(status().isForbidden());

        verifyNoInteractions(syncScanBatch);
    }

    // ---------- bounds (BVA) ----------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 499, 500})
    @DisplayName("1 to 500 scans is 200, with one result per scan")
    void insideTheBounds_is200(int size) throws Exception {
        send(staffToken(), batchOf(size))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(size))
                .andExpect(jsonPath("$.results[0].outcome").value("ACCEPTED"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    @DisplayName("0 or 501 scans is 400 and nothing is processed")
    void outsideTheBounds_is400(int size) throws Exception {
        send(staffToken(), batchOf(size)).andExpect(status().isBadRequest());

        verifyNoInteractions(syncScanBatch);
    }

    @Test
    @DisplayName("a body without scans, or with scans that is not a list, is 400")
    void missingScans_is400() throws Exception {
        send(staffToken(), "{}").andExpect(status().isBadRequest());
        send(staffToken(), "{\"scans\":\"x\"}").andExpect(status().isBadRequest());
        send(staffToken(), "").andExpect(status().isBadRequest());

        verifyNoInteractions(syncScanBatch);
    }

    // ---------- malformed scans fail the batch ----------

    @Test
    @DisplayName("the caller is the token's subject, and the scan is handed over as sent")
    void callerComesFromTheToken() throws Exception {
        var id = UUID.randomUUID();

        send(staffToken(), batch(verified(id, "ABC"))).andExpect(status().isOk());

        var command = ArgumentCaptor.forClass(SyncScanBatchCommand.class);
        verify(syncScanBatch).sync(command.capture());
        assertThat(command.getValue().actor().userId().value()).isEqualTo(STAFF);
        var scan = command.getValue().scans().get(0);
        assertThat(scan.id()).isEqualTo(new ScanId(id));
        assertThat(scan.verdict()).isEqualTo(DeviceVerdict.VERIFIED);
        assertThat(scan.ticketCode()).isEqualTo("ABC");
        assertThat(scan.venue().value()).isEqualTo(ROOM);
        assertThat(scan.deviceId()).isEqualTo("device-1");
    }

    @Test
    @DisplayName("a scanner named in the body is ignored: the body has no such field to read")
    void scannerInTheBody_isIgnored() throws Exception {
        var body =
                "{\"scans\":[{\"id\":\"%s\",\"deviceVerdict\":\"VERIFIED\",\"ticketCode\":\"A\","
                                .formatted(UUID.randomUUID())
                        + "\"workshopId\":\"%s\",\"scannedAt\":\"2026-12-01T09:00:00Z\","
                                .formatted(ROOM)
                        + "\"deviceId\":\"d\",\"scannedBy\":\"%s\"}]}".formatted(UUID.randomUUID());

        send(staffToken(), body).andExpect(status().isOk());

        var command = ArgumentCaptor.forClass(SyncScanBatchCommand.class);
        verify(syncScanBatch).sync(command.capture());
        assertThat(command.getValue().actor().userId().value()).isEqualTo(STAFF);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"deviceVerdict\":\"VERIFIED\",\"ticketCode\":\"A\"}",
                "{\"id\":\"not-a-uuid\",\"deviceVerdict\":\"VERIFIED\",\"ticketCode\":\"A\"}",
            })
    @DisplayName("a scan missing a field, or with an id that is not a UUID, fails the batch")
    void malformedScan_is400(String partial) throws Exception {
        send(staffToken(), "{\"scans\":[" + partial + "]}").andExpect(status().isBadRequest());

        verifyNoInteractions(syncScanBatch);
    }

    @Test
    @DisplayName("a deviceId of 100 characters is accepted, one of 101 is 400")
    void deviceIdBoundary() throws Exception {
        var id = UUID.randomUUID();
        send(staffToken(), withDevice(id, "d".repeat(100))).andExpect(status().isOk());
        send(staffToken(), withDevice(id, "d".repeat(101))).andExpect(status().isBadRequest());
        send(staffToken(), withDevice(id, "")).andExpect(status().isBadRequest());
    }

    private static String withDevice(UUID id, String device) {
        return batch(verified(id, "A").replace("\"device-1\"", "\"" + device + "\""));
    }

    @Test
    @DisplayName("an unknown verdict, or a scannedAt that is not a date, is 400")
    void unknownVerdict_orBadDate_is400() throws Exception {
        send(staffToken(), batch(scan(UUID.randomUUID(), "MAYBE", "A")))
                .andExpect(status().isBadRequest());
        send(
                        staffToken(),
                        batch(
                                verified(UUID.randomUUID(), "A")
                                        .replace("2026-12-01T09:00:00Z", "soon")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "7", "{\"a\":1}", "[\"A\"]", "\"\""})
    @DisplayName("a VERIFIED scan whose ticketCode is missing, empty or not a string is 400")
    void verifiedWithoutAUsableCode_is400(String rawCode) throws Exception {
        var body =
                batch(
                        verified(UUID.randomUUID(), null)
                                .replace("\"ticketCode\":null", "\"ticketCode\":" + rawCode));

        send(staffToken(), body).andExpect(status().isBadRequest());

        verifyNoInteractions(syncScanBatch);
    }

    @Test
    @DisplayName("a VERIFIED scan with no ticketCode member at all is 400")
    void verifiedWithoutTheMember_is400() throws Exception {
        var body = batch(verified(UUID.randomUUID(), null).replace("\"ticketCode\":null,", ""));

        send(staffToken(), body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a VERIFIED ticketCode of 64 characters is accepted, one of 65 is 400")
    void ticketCodeBoundary() throws Exception {
        send(staffToken(), batch(verified(UUID.randomUUID(), "A".repeat(64))))
                .andExpect(status().isOk());
        send(staffToken(), batch(verified(UUID.randomUUID(), "A".repeat(65))))
                .andExpect(status().isBadRequest());
    }

    // ---------- the poison batch (AC50.3) ----------

    @ParameterizedTest
    @ValueSource(
            strings = {"null", "7", "true", "{\"a\":[1,2]}", "[\"A\",\"B\"]", "\"\"", "\"%s\""})
    @DisplayName("a REJECTED_SIGNATURE scan never fails the batch, whatever its ticketCode is")
    void rejectedSignature_neverFailsTheBatch(String rawCode) throws Exception {
        var code = rawCode.replace("%s", "X".repeat(10_000));
        var hostile =
                scan(UUID.randomUUID(), "REJECTED_SIGNATURE", null)
                        .replace("\"ticketCode\":null", "\"ticketCode\":" + code);
        var fine = verified(UUID.randomUUID(), "GOOD");

        send(staffToken(), batch(hostile, fine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2));

        var command = ArgumentCaptor.forClass(SyncScanBatchCommand.class);
        verify(syncScanBatch).sync(command.capture());
        var handed = command.getValue().scans();
        assertThat(handed).hasSize(2);
        assertThat(handed.get(0).verdict()).isEqualTo(DeviceVerdict.REJECTED_SIGNATURE);
        assertThat(handed.get(0).ticketCode()).as("never reaches the use case").isNull();
        assertThat(handed.get(1).ticketCode()).isEqualTo("GOOD");
    }

    @Test
    @DisplayName("a REJECTED_SIGNATURE scan with no ticketCode member is fine too")
    void rejectedSignature_withoutTheMember_isFine() throws Exception {
        var scan =
                scan(UUID.randomUUID(), "REJECTED_SIGNATURE", null)
                        .replace("\"ticketCode\":null,", "");

        send(staffToken(), batch(scan)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("every other field of a REJECTED_SIGNATURE scan is still validated")
    void rejectedSignature_otherFieldsAreChecked() throws Exception {
        var scan =
                scan(UUID.randomUUID(), "REJECTED_SIGNATURE", null)
                        .replace("\"device-1\"", "\"" + "d".repeat(101) + "\"");

        send(staffToken(), batch(scan)).andExpect(status().isBadRequest());
    }

    // ---------- the response ----------

    @Test
    @DisplayName("the response is a list of {id, outcome} and nothing about the ticket")
    void responseShape() throws Exception {
        var id = UUID.randomUUID();
        doReturn(
                        new SyncScanBatchResult(
                                List.of(
                                        new ScanResult(
                                                new ScanId(id), ScanOutcome.REJECTED_DUPLICATE))))
                .when(syncScanBatch)
                .sync(any());

        send(staffToken(), batch(verified(id, "A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value(id.toString()))
                .andExpect(jsonPath("$.results[0].outcome").value("REJECTED_DUPLICATE"))
                .andExpect(jsonPath("$.results[0].ticketCode").doesNotExist());
    }
}
