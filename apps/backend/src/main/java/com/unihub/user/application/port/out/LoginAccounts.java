package com.unihub.user.application.port.out;

import com.unihub.shared.domain.id.UserId;
import java.util.Optional;

/** The one {@code users} lookup login needs. Reads the Master. */
public interface LoginAccounts {

    /** The account whose {@code email} equals this exactly; empty when nobody holds it. */
    Optional<LoginAccount> findByEmail(String email);

    /**
     * {@code passwordHash} is null for a legacy-synced student who has not completed first-login
     * setup (user.md §10).
     */
    record LoginAccount(
            UserId id, String email, String fullName, String passwordHash, boolean active) {}
}
