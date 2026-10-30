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
    @DisplayName("seeds the four default permission codes (V2)")
    void seedsDefaultPermissions() {
        var codes = jdbc.queryForList("SELECT code FROM permissions", String.class);

        assertThat(codes)
                .contains("WORKSHOP_CREATE", "WORKSHOP_EDIT", "CHECKIN_SCAN", "REPORT_VIEW");
    }

    @Test
    @DisplayName("Organizer bundles WORKSHOP_CREATE, WORKSHOP_EDIT and REPORT_VIEW (V6, D46)")
    void organizerBundle() {
        assertThat(permissionCodesOfRole("Organizer"))
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT", "REPORT_VIEW");
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
    @DisplayName("V4 seeds REGISTRATION_CREATE (D7)")
    void seedsRegistrationCreate() {
        assertThat(jdbc.queryForList("SELECT code FROM permissions", String.class))
                .contains("REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("Student bundles only REGISTRATION_CREATE — no administrative permission")
    void studentBundle() {
        assertThat(permissionCodesOfRole("Student")).containsExactly("REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("no other role holds REGISTRATION_CREATE (AC7.2)")
    void otherRolesLackRegistrationCreate() {
        assertThat(permissionCodesOfRole("Organizer")).doesNotContain("REGISTRATION_CREATE");
        assertThat(permissionCodesOfRole("Admin")).doesNotContain("REGISTRATION_CREATE");
        assertThat(permissionCodesOfRole("Check-in Staff")).doesNotContain("REGISTRATION_CREATE");
    }

    @Test
    @DisplayName("V4 is idempotent: running its statements again adds nothing")
    void v4IsIdempotent() throws Exception {
        var sql =
                new String(
                        getClass()
                                .getResourceAsStream(
                                        "/db/migration/V4__seed_registration_permission.sql")
                                .readAllBytes());
        var before = jdbc.queryForObject("SELECT count(*) FROM role_permissions", Long.class);

        for (var statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions", Long.class))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM permissions WHERE code = 'REGISTRATION_CREATE'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("V6 gives Organizer REPORT_VIEW once: its seed statement run again adds nothing")
    void v6SeedIsIdempotent() throws Exception {
        var sql =
                new String(
                        getClass()
                                .getResourceAsStream(
                                        "/db/migration/V6__checkin_unresolved_scans_and_organizer_reports.sql")
                                .readAllBytes());
        var before = jdbc.queryForObject("SELECT count(*) FROM role_permissions", Long.class);

        // Only the seed: the DDL statements of V6 are not meant to run twice.
        for (var statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (statement.strip().startsWith("INSERT")) {
                jdbc.execute(statement);
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions", Long.class))
                .isEqualTo(before);
        assertThat(permissionCodesOfRole("Organizer")).containsOnlyOnce("REPORT_VIEW");
    }

    @Test
    @DisplayName("REPORT_VIEW stays with Organizer and Admin: Student and Check-in Staff lack it")
    void reportViewIsNotForStudentsOrStaff() {
        assertThat(permissionCodesOfRole("Student")).doesNotContain("REPORT_VIEW");
        assertThat(permissionCodesOfRole("Check-in Staff")).doesNotContain("REPORT_VIEW");
    }
}
