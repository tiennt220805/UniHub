package com.unihub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PermissionsTest {

    @Test
    @DisplayName(
            "constants equal the permissions.code values seeded by V2/V4 (renaming one breaks RBAC)")
    void constants_equalSeededCodes() {
        assertThat(Permissions.WORKSHOP_CREATE).isEqualTo("WORKSHOP_CREATE");
        assertThat(Permissions.WORKSHOP_EDIT).isEqualTo("WORKSHOP_EDIT");
        assertThat(Permissions.CHECKIN_SCAN).isEqualTo("CHECKIN_SCAN");
        assertThat(Permissions.REPORT_VIEW).isEqualTo("REPORT_VIEW");
        assertThat(Permissions.REGISTRATION_CREATE).isEqualTo("REGISTRATION_CREATE");
    }
}
