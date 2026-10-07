package com.unihub.user.application.port.out;

import com.unihub.shared.domain.id.UserId;
import java.util.Set;

public interface UserPermissionQueries {

    /**
     * The distinct {@code permissions.code} values granted to the user through every role assigned
     * to them; empty when the user has no role, the roles grant nothing, or the user is unknown.
     * Never null.
     */
    Set<String> permissionCodesOf(UserId userId);
}
