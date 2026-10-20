package com.unihub.user.application.usecase;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.user.application.command.LoginCommand;
import com.unihub.user.application.port.in.LoginUseCase;
import com.unihub.user.application.port.out.AccessTokenIssuer;
import com.unihub.user.application.port.out.LoginAccounts;
import com.unihub.user.application.port.out.Passwords;
import com.unihub.user.application.port.out.UserPermissionQueries;
import com.unihub.user.application.port.out.UserRoleQueries;
import com.unihub.user.application.result.LoginResult;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates a user and issues the HS256 auth JWT (auth-and-identity.md §2–§4, §7). The status
 * check comes after the password check, and every refusal looks the same, so a caller learns
 * nothing about why an attempt failed. Suspending a user blocks new logins only: a token issued
 * before keeps working until {@code exp} (§4, architecture.md §5.2).
 *
 * <p>Reads the Master: a permission or status change must apply to the very next login rather than
 * lag on a replica (§20).
 */
@Service
class Login implements LoginUseCase {

    private final LoginAccounts accounts;
    private final UserPermissionQueries permissions;
    private final UserRoleQueries roles;
    private final Passwords passwords;
    private final AccessTokenIssuer tokens;
    private final Clock clock;

    Login(
            LoginAccounts accounts,
            UserPermissionQueries permissions,
            UserRoleQueries roles,
            Passwords passwords,
            AccessTokenIssuer tokens,
            Clock clock) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.roles = roles;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    @ReadFromPrimary
    @Transactional(readOnly = true)
    public LoginResult login(LoginCommand command) {
        var account = accounts.findByEmail(command.email().strip());
        // Hash check runs even for an unknown email or a missing hash (see Passwords.matches).
        var passwordMatches =
                passwords.matches(
                        command.password(), account.map(a -> a.passwordHash()).orElse(null));
        if (account.isEmpty() || !passwordMatches || !account.get().active()) {
            return new LoginResult.Rejected();
        }
        var user = account.get();
        var granted = permissions.permissionCodesOf(user.id());
        var token = tokens.issue(user.id(), granted, clock.instant());
        return new LoginResult.Authenticated(
                user.id(),
                user.fullName(),
                user.email(),
                roles.roleNamesOf(user.id()),
                granted,
                token.value(),
                (int) token.lifetime().toSeconds());
    }
}
