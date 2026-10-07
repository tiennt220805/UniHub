package com.unihub.user.application.result;

import com.unihub.shared.domain.id.UserId;
import java.util.Objects;
import java.util.Set;

public record ResolveUserPermissionsResult(UserId userId, Set<String> permissions) {

    public ResolveUserPermissionsResult {
        Objects.requireNonNull(userId);
        permissions = Set.copyOf(permissions);
    }
}
