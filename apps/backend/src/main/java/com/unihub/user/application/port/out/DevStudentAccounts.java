package com.unihub.user.application.port.out;

/**
 * Writes the dev/test Student accounts (user.md §10). Never used outside the {@code dev} profile.
 */
public interface DevStudentAccounts {

    /**
     * Creates an {@code ACTIVE} account holding the Student role, unless the email is already
     * taken. An existing account is not modified.
     *
     * @return whether a row was created
     */
    boolean createIfAbsent(String email, String fullName, String passwordHash);
}
