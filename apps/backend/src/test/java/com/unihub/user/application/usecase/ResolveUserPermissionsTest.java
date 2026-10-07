package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.port.out.UserPermissionQueries;
import com.unihub.user.application.result.ResolveUserPermissionsResult;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResolveUserPermissionsTest {

    private final UserPermissionQueries permissions = mock(UserPermissionQueries.class);
    private final ResolveUserPermissions useCase = new ResolveUserPermissions(permissions);

    @Test
    @DisplayName("returns exactly the permission codes the port reports for the user")
    void resolve_returnsPermissionCodesFromPort() {
        var userId = UserId.newId();
        when(permissions.permissionCodesOf(userId))
                .thenReturn(Set.of("WORKSHOP_CREATE", "WORKSHOP_EDIT"));

        ResolveUserPermissionsResult result = useCase.resolve(userId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.permissions())
                .containsExactlyInAnyOrder("WORKSHOP_CREATE", "WORKSHOP_EDIT");
    }

    @Test
    @DisplayName("returns an empty set, not null, for a user with no permissions")
    void resolve_returnsEmptySet_whenUserHasNoPermissions() {
        var userId = UserId.newId();
        when(permissions.permissionCodesOf(userId)).thenReturn(Set.of());

        assertThat(useCase.resolve(userId).permissions()).isEmpty();
    }

    @Test
    @DisplayName("the returned permission set cannot be modified by the caller")
    void resolve_returnsUnmodifiableSet() {
        var userId = UserId.newId();
        when(permissions.permissionCodesOf(userId)).thenReturn(Set.of("CHECKIN_SCAN"));

        var result = useCase.resolve(userId);

        assertThatThrownBy(() -> result.permissions().add("REPORT_VIEW"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
