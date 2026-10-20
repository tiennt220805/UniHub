package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.command.LoginCommand;
import com.unihub.user.application.port.out.AccessTokenIssuer;
import com.unihub.user.application.port.out.AccessTokenIssuer.IssuedAccessToken;
import com.unihub.user.application.port.out.LoginAccounts;
import com.unihub.user.application.port.out.LoginAccounts.LoginAccount;
import com.unihub.user.application.port.out.Passwords;
import com.unihub.user.application.port.out.UserPermissionQueries;
import com.unihub.user.application.port.out.UserRoleQueries;
import com.unihub.user.application.result.LoginResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
    private static final String HASH = "stored-hash";

    private final LoginAccounts accounts = mock(LoginAccounts.class);
    private final UserPermissionQueries permissions = mock(UserPermissionQueries.class);
    private final UserRoleQueries roles = mock(UserRoleQueries.class);
    private final Passwords passwords = mock(Passwords.class);
    private final AccessTokenIssuer tokens = mock(AccessTokenIssuer.class);
    private final Login useCase =
            new Login(
                    accounts,
                    permissions,
                    roles,
                    passwords,
                    tokens,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final UserId userId = UserId.newId();

    private LoginAccount account(String hash, boolean active) {
        return new LoginAccount(userId, "a@example.test", "Ann", hash, active);
    }

    private void givenAccount(LoginAccount account) {
        when(accounts.findByEmail("a@example.test")).thenReturn(Optional.of(account));
    }

    private LoginResult login(String password) {
        return useCase.login(new LoginCommand("a@example.test", password));
    }

    @Test
    @DisplayName("right password on an ACTIVE account: token over the resolved permissions")
    void success() {
        givenAccount(account(HASH, true));
        when(passwords.matches("pw", HASH)).thenReturn(true);
        when(permissions.permissionCodesOf(userId)).thenReturn(Set.of("REGISTRATION_CREATE"));
        when(roles.roleNamesOf(userId)).thenReturn(List.of("Student"));
        when(tokens.issue(userId, Set.of("REGISTRATION_CREATE"), NOW))
                .thenReturn(new IssuedAccessToken("jwt", Duration.ofMinutes(30)));

        var result = login("pw");

        assertThat(result).isInstanceOf(LoginResult.Authenticated.class);
        var ok = (LoginResult.Authenticated) result;
        assertThat(ok.userId()).isEqualTo(userId);
        assertThat(ok.fullName()).isEqualTo("Ann");
        assertThat(ok.email()).isEqualTo("a@example.test");
        assertThat(ok.roles()).containsExactly("Student");
        assertThat(ok.permissions()).containsExactly("REGISTRATION_CREATE");
        assertThat(ok.accessToken()).isEqualTo("jwt");
        assertThat(ok.expiresInSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("surrounding whitespace in the email is ignored")
    void emailIsStripped() {
        givenAccount(account(HASH, true));
        when(passwords.matches("pw", HASH)).thenReturn(true);
        when(permissions.permissionCodesOf(userId)).thenReturn(Set.of());
        when(roles.roleNamesOf(userId)).thenReturn(List.of());
        when(tokens.issue(any(), any(), any()))
                .thenReturn(new IssuedAccessToken("jwt", Duration.ofMinutes(30)));

        var result = useCase.login(new LoginCommand("  a@example.test ", "pw"));

        assertThat(result).isInstanceOf(LoginResult.Authenticated.class);
    }

    @Test
    @DisplayName("wrong password: rejected, no token")
    void wrongPassword() {
        givenAccount(account(HASH, true));
        when(passwords.matches("bad", HASH)).thenReturn(false);

        assertThat(login("bad")).isInstanceOf(LoginResult.Rejected.class);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("SUSPENDED account with the right password: rejected, no token (S-9)")
    void suspended() {
        givenAccount(account(HASH, false));
        when(passwords.matches("pw", HASH)).thenReturn(true);

        assertThat(login("pw")).isInstanceOf(LoginResult.Rejected.class);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("no password set yet (legacy-synced student): rejected like a wrong password")
    void noPasswordYet() {
        givenAccount(account(null, true));
        when(passwords.matches("pw", null)).thenReturn(false);

        assertThat(login("pw")).isInstanceOf(LoginResult.Rejected.class);
    }

    @Test
    @DisplayName("unknown email: rejected, and the password check still runs (no timing tell)")
    void unknownEmail() {
        when(accounts.findByEmail("a@example.test")).thenReturn(Optional.empty());

        assertThat(login("pw")).isInstanceOf(LoginResult.Rejected.class);
        verify(passwords).matches("pw", null);
        verify(tokens, never()).issue(any(), any(), any());
    }
}
