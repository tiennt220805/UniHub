package com.unihub.user.infrastructure.in.seeding;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dev-only Student accounts (user.md §10). {@code password} has no default and is never
 * committed: it comes from {@code UNIHUB_DEV_SEED_STUDENT_PASSWORD}, and without it nothing is
 * seeded.
 */
@ConfigurationProperties(prefix = "unihub.dev-seed")
public record DevStudentSeedProperties(String studentPassword, List<Student> students) {

    private static final List<Student> DEFAULT_STUDENTS =
            List.of(
                    new Student("student1@dev.unihub.local", "Dev Student One"),
                    new Student("student2@dev.unihub.local", "Dev Student Two"),
                    new Student("student3@dev.unihub.local", "Dev Student Three"));

    public DevStudentSeedProperties {
        students =
                students == null || students.isEmpty() ? DEFAULT_STUDENTS : List.copyOf(students);
    }

    public record Student(String email, String fullName) {}

    boolean hasPassword() {
        return studentPassword != null && !studentPassword.isBlank();
    }

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "DevStudentSeedProperties[studentPassword=****, students=" + students + "]";
    }
}
