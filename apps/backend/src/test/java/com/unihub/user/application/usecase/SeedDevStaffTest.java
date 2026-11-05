package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.user.application.command.SeedDevStaffCommand;
import com.unihub.user.application.command.SeedDevStaffCommand.DevStaffAccount;
import com.unihub.user.application.command.SeedDevStaffCommand.StaffRole;
import com.unihub.user.application.port.out.DevStaffAccounts;
import com.unihub.user.application.port.out.Passwords;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeedDevStaffTest {

    private final DevStaffAccounts accounts = mock(DevStaffAccounts.class);
    private final Passwords passwords = mock(Passwords.class);
    private final SeedDevStaff useCase = new SeedDevStaff(accounts, passwords);

    @Test
    @DisplayName(
            "each account is stored with the hash of its own password and the name of its role")
    void seed_hashesEachPasswordAndPassesTheRoleName() {
        when(passwords.hash("organizer-pw")).thenReturn("HASH-O");
        when(passwords.hash("admin-pw")).thenReturn("HASH-A");
        when(passwords.hash("checkin-pw")).thenReturn("HASH-C");
        when(accounts.createIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        var created =
                useCase.seed(
                        new SeedDevStaffCommand(
                                List.of(
                                        new DevStaffAccount(
                                                "o@dev.test",
                                                "O",
                                                StaffRole.ORGANIZER,
                                                "organizer-pw"),
                                        new DevStaffAccount(
                                                "a@dev.test", "A", StaffRole.ADMIN, "admin-pw"),
                                        new DevStaffAccount(
                                                "c@dev.test",
                                                "C",
                                                StaffRole.CHECKIN_STAFF,
                                                "checkin-pw"))));

        assertThat(created).isEqualTo(3);
        verify(accounts).createIfAbsent("o@dev.test", "O", "HASH-O", "Organizer");
        verify(accounts).createIfAbsent("a@dev.test", "A", "HASH-A", "Admin");
        verify(accounts).createIfAbsent("c@dev.test", "C", "HASH-C", "Check-in Staff");
    }

    @Test
    @DisplayName("counts only the accounts it created; one that exists is not an error")
    void seed_countsOnlyCreated() {
        when(passwords.hash("pw")).thenReturn("HASH");
        when(accounts.createIfAbsent("o@dev.test", "O", "HASH", "Organizer")).thenReturn(false);
        when(accounts.createIfAbsent("a@dev.test", "A", "HASH", "Admin")).thenReturn(true);

        var created =
                useCase.seed(
                        new SeedDevStaffCommand(
                                List.of(
                                        new DevStaffAccount(
                                                "o@dev.test", "O", StaffRole.ORGANIZER, "pw"),
                                        new DevStaffAccount(
                                                "a@dev.test", "A", StaffRole.ADMIN, "pw"))));

        assertThat(created).isEqualTo(1);
    }

    @Test
    @DisplayName("the plaintext never reaches the port, and the Student role is not a staff role")
    void seed_neverPassesThePlaintext_andStudentIsNotAStaffRole() {
        when(passwords.hash("plain")).thenReturn("HASH");

        useCase.seed(
                new SeedDevStaffCommand(
                        List.of(
                                new DevStaffAccount(
                                        "o@dev.test", "O", StaffRole.ORGANIZER, "plain"))));

        verify(accounts, never()).createIfAbsent("o@dev.test", "O", "plain", "Organizer");
        assertThat(StaffRole.values())
                .extracting(StaffRole::roleName)
                .containsExactly("Organizer", "Admin", "Check-in Staff");
    }
}
