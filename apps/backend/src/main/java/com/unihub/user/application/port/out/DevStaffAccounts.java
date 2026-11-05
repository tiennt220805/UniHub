package com.unihub.user.application.port.out;

/** Writes the dev/test staff accounts (user.md §10). Never used outside the {@code dev} profile. */
public interface DevStaffAccounts {

    /**
     * Creates an {@code ACTIVE} account holding the named role, unless the email is already taken.
     * An existing account is not modified, and so keeps the role and the password it has.
     *
     * @return whether a row was created
     * @throws IllegalStateException when the role does not exist (the seed migration has not run);
     *     nothing is created then
     */
    boolean createIfAbsent(String email, String fullName, String passwordHash, String roleName);
}
