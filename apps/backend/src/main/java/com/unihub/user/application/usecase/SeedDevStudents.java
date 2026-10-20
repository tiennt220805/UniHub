package com.unihub.user.application.usecase;

import com.unihub.user.application.command.SeedDevStudentsCommand;
import com.unihub.user.application.port.in.SeedDevStudentsUseCase;
import com.unihub.user.application.port.out.DevStudentAccounts;
import com.unihub.user.application.port.out.Passwords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives local development and demos a few Students who can sign in (user.md §10, Phase 2 checklist
 * D9). Nothing triggers it except the {@code dev}-profile runner; no production path does.
 */
@Service
class SeedDevStudents implements SeedDevStudentsUseCase {

    private final DevStudentAccounts accounts;
    private final Passwords passwords;

    SeedDevStudents(DevStudentAccounts accounts, Passwords passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public int seed(SeedDevStudentsCommand command) {
        var hash = passwords.hash(command.password());
        var created = 0;
        for (var student : command.students()) {
            if (accounts.createIfAbsent(student.email(), student.fullName(), hash)) {
                created++;
            }
        }
        return created;
    }
}
