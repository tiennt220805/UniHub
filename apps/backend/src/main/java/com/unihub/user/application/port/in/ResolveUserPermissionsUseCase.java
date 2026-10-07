package com.unihub.user.application.port.in;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.application.result.ResolveUserPermissionsResult;

public interface ResolveUserPermissionsUseCase {

    ResolveUserPermissionsResult resolve(UserId userId);
}
