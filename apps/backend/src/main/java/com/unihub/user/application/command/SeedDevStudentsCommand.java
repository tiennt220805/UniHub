package com.unihub.user.application.command;

import java.util.List;
import java.util.Objects;

/** The dev/test Student accounts to ensure exist, all sharing one password. */
public record SeedDevStudentsCommand(List<DevStudent> students, String password) {

    public SeedDevStudentsCommand {
        students = List.copyOf(students);
        Objects.requireNonNull(password);
    }

    public record DevStudent(String email, String fullName) {}

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "SeedDevStudentsCommand[students=" + students + ", password=****]";
    }
}
