package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.command.SeedDevStudentsCommand.DevStudent;
import com.unihub.user.application.port.out.DevStudentAccounts;
import com.unihub.user.application.port.out.Passwords;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeedDevStudentsTest {

    private final DevStudentAccounts accounts = mock(DevStudentAccounts.class);
    private final Passwords passwords = mock(Passwords.class);
    private final SeedDevStudents useCase = new SeedDevStudents(accounts, passwords);

    @Test
    @DisplayName("stores only the hash, and counts just the accounts it actually created")
    void seed_countsCreated_storesHashOnly() {
        when(passwords.hash("secret")).thenReturn("HASH");
        when(accounts.createIfAbsent("a@dev.test", "A", "HASH")).thenReturn(true);
        when(accounts.createIfAbsent("b@dev.test", "B", "HASH")).thenReturn(false);

        var created =
                useCase.seed(
                        new SeedDevStudentsCommand(
                                List.of(
                                        new DevStudent("a@dev.test", "A"),
                                        new DevStudent("b@dev.test", "B")),
                                "secret"));

        assertThat(created).isEqualTo(1);
        verify(accounts).createIfAbsent("a@dev.test", "A", "HASH");
    }
}
