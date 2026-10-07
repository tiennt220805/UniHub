package com.unihub.user.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.RolledBackJdbcSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default roles/permissions travel as the V2 Flyway seed (deployment-and-cicd.md §4), through
 * the same single-execution pipeline as DDL. Read-only: the seed is migrated once by
 * IntegrationTestSupport, so these tests assert what a freshly migrated database contains.
 */
class DefaultRbacSeedIT extends RolledBackJdbcSupport {

    private List<String> permissionCodesOfRole(String roleName) {
        return jdbc.queryForList(
                "SELECT p.code FROM roles r"
                        + " JOIN role_permissions rp ON rp.role_id = r.id"
                        + " JOIN permissions p ON p.id = rp.permission_id"
                        + " WHERE r.name = ?",
                String.class,
                roleName);
    }

    @Test
    @DisplayName("seeds the four default roles")
    void seedsDefaultRoles() {
        var names = jdbc.queryForList("SELECT name FROM roles", String.class);

        assertThat(names).contains("Student", "Organizer", "Admin", "Check-in Staff");
    }

    @Test
    @DisplayName("seeds the four default permission codes")
    void seedsDefaultPermissions() {
        var codes = jdbc.queryForList("SELECT code FROM permissions", String.class);

        assertThat(codes)
                .contains("WORKSHOP_CREATE", "WORKSHOP_EDIT", "CHECKIN_SCAN", "REPORT_VIEW");
    }

    @Test
    @DisplayName("Organizer bundles WORKSHOP_CREATE and WORKSHOP_EDIT")
    void organizerBundle() {
        assertThat(permissionCodesOfRole("Organizer"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT");
    }

    @Test
    @DisplayName("Admin bundles WORKSHOP_CREATE, WORKSHOP_EDIT and REPORT_VIEW")
    void adminBundle() {
        assertThat(permissionCodesOfRole("Admin"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT", "REPORT_VIEW");
    }

    @Test
    @DisplayName("Check-in Staff bundles only CHECKIN_SCAN")
    void checkinStaffBundle() {
        assertThat(permissionCodesOfRole("Check-in Staff")).containsExactly("CHECKIN_SCAN");
    }

    @Test
    @DisplayName("Student holds no administrative permission")
    void studentBundleIsEmpty() {
        assertThat(permissionCodesOfRole("Student")).isEmpty();
    }
}
