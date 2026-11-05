package com.unihub.user.application.usecase;

import com.unihub.user.application.command.SeedDevStaffCommand;
import com.unihub.user.application.port.in.SeedDevStaffUseCase;
import com.unihub.user.application.port.out.DevStaffAccounts;
import com.unihub.user.application.port.out.Passwords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a development or cluster environment staff accounts that can sign in (user.md §10, Phase 7
 * checklist 5.8). Idempotent: an email that already exists is left as it is, so several API pods
 * starting together, or a restart, create each account once. Goes through the same {@code
 * users}/{@code user_roles} writer as the Student seeder, never around the User module.
 */
@Service
class SeedDevStaff implements SeedDevStaffUseCase {

    private final DevStaffAccounts accounts;
    private final Passwords passwords;

    SeedDevStaff(DevStaffAccounts accounts, Passwords passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public int seed(SeedDevStaffCommand command) {
        var created = 0;
        for (var account : command.accounts()) {
            var hash = passwords.hash(account.password());
            if (accounts.createIfAbsent(
                    account.email(), account.fullName(), hash, account.role().roleName())) {
                created++;
            }
        }
        return created;
    }
}
