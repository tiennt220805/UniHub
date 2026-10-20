package com.unihub.user.application.port.out;

public interface Passwords {

    /** A salted one-way hash fit for {@code users.password_hash}. */
    String hash(String rawPassword);

    /**
     * Whether {@code rawPassword} matches {@code storedHash}. A null hash (no password set yet)
     * never matches, but costs the same as a real check, so response time does not reveal that an
     * account exists or has no password.
     */
    boolean matches(String rawPassword, String storedHash);
}
