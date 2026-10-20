package com.unihub.user.application.command;

import java.util.Objects;

/** Credentials as typed; the use case never logs or stores {@code password}. */
public record LoginCommand(String email, String password) {

    public LoginCommand {
        Objects.requireNonNull(email);
        Objects.requireNonNull(password);
    }

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "LoginCommand[email=" + email + ", password=****]";
    }
}
