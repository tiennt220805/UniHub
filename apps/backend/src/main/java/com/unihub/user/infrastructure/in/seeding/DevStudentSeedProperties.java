package com.unihub.user.infrastructure.in.seeding;

import com.unihub.user.application.command.SeedDevStaffCommand.StaffRole;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dev-only accounts (user.md §10): the Students ({@code student-password}) and, since Phase 7
 * task 5.8, one account for each staff role (Organizer, Admin, Check-in Staff), each with a
 * password of its own. No password has a default and none is ever committed: each comes from its
 * environment variable ({@code UNIHUB_DEV_SEED_STUDENT_PASSWORD}, {@code ..._ORGANIZER_PASSWORD},
 * {@code ..._ADMIN_PASSWORD}, {@code ..._CHECKIN_STAFF_PASSWORD}), and the accounts of a group
 * whose password is unset are not seeded.
 */
@ConfigurationProperties(prefix = "unihub.dev-seed")
public record DevStudentSeedProperties(
        String studentPassword,
        List<Student> students,
        String organizerPassword,
        String adminPassword,
        String checkinStaffPassword,
        List<Staff> staff) {

    private static final List<Student> DEFAULT_STUDENTS =
            List.of(
                    new Student("student1@dev.unihub.local", "Dev Student One"),
                    new Student("student2@dev.unihub.local", "Dev Student Two"),
                    new Student("student3@dev.unihub.local", "Dev Student Three"));

    private static final List<Staff> DEFAULT_STAFF =
            List.of(
                    new Staff("organizer1@dev.unihub.local", "Dev Organizer", StaffRole.ORGANIZER),
                    new Staff("admin1@dev.unihub.local", "Dev Admin", StaffRole.ADMIN),
                    new Staff(
                            "staff1@dev.unihub.local",
                            "Dev Check-in Staff",
                            StaffRole.CHECKIN_STAFF));

    public DevStudentSeedProperties {
        students =
                students == null || students.isEmpty() ? DEFAULT_STUDENTS : List.copyOf(students);
        staff = staff == null || staff.isEmpty() ? DEFAULT_STAFF : List.copyOf(staff);
    }

    public record Student(String email, String fullName) {}

    public record Staff(String email, String fullName, StaffRole role) {}

    boolean hasPassword() {
        return studentPassword != null && !studentPassword.isBlank();
    }

    /** The password of the group this role belongs to, or {@code null} when it is not set. */
    String passwordOf(StaffRole role) {
        var password =
                switch (role) {
                    case ORGANIZER -> organizerPassword;
                    case ADMIN -> adminPassword;
                    case CHECKIN_STAFF -> checkinStaffPassword;
                };
        return password == null || password.isBlank() ? null : password;
    }

    /** Keeps the passwords out of logs. */
    @Override
    public String toString() {
        return "DevStudentSeedProperties[passwords=****, students="
                + students
                + ", staff="
                + staff
                + "]";
    }
}
