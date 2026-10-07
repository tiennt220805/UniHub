package com.unihub.user.application.usecase;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.port.in.ResolveUserPermissionsUseCase;
import com.unihub.user.application.port.out.UserPermissionQueries;
import com.unihub.user.application.result.ResolveUserPermissionsResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves User → Role → Permission into the flat permission list a JWT will carry
 * (auth-and-identity.md §2). Deliberately has no driving adapter yet: token issuance arrives with
 * the real login endpoint. When it does, add {@code @ReadFromPrimary} (§20) so a permission change
 * is visible at issuance rather than lagging on a replica.
 */
@Service
class ResolveUserPermissions implements ResolveUserPermissionsUseCase {

    private final UserPermissionQueries permissions;

    ResolveUserPermissions(UserPermissionQueries permissions) {
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public ResolveUserPermissionsResult resolve(UserId userId) {
        return new ResolveUserPermissionsResult(userId, permissions.permissionCodesOf(userId));
    }
}
