package com.unihub.user.application.command;

import java.util.List;
import java.util.Objects;

/**
 * The dev/test accounts that hold a staff role to ensure exist (user.md §10, Phase 7 checklist
 * 5.8). Each carries its own password: the Organizer, the Admin and the Check-in Staff accounts of
 * an environment do not share one.
 */
public record SeedDevStaffCommand(List<DevStaffAccount> accounts) {

    public SeedDevStaffCommand {
        accounts = List.copyOf(accounts);
    }

    /**
     * The roles of V2's seed that a person on the staff holds. Never {@code Student}: that is its
     * own seeder.
     */
    public enum StaffRole {
        ORGANIZER("Organizer"),
        ADMIN("Admin"),
        CHECKIN_STAFF("Check-in Staff");

        private final String roleName;

        StaffRole(String roleName) {
            this.roleName = roleName;
        }

        /** The value of {@code roles.name}. */
        public String roleName() {
            return roleName;
        }
    }

    public record DevStaffAccount(String email, String fullName, StaffRole role, String password) {

        public DevStaffAccount {
            Objects.requireNonNull(email);
            Objects.requireNonNull(fullName);
            Objects.requireNonNull(role);
            Objects.requireNonNull(password);
        }

        /** Keeps the password out of logs. */
        @Override
        public String toString() {
            return "DevStaffAccount[email=" + email + ", role=" + role + ", password=****]";
        }
    }
}
