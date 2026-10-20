package com.unihub.user.application.port.out;

import com.unihub.shared.domain.id.UserId;
import java.util.List;

public interface UserRoleQueries {

    /**
     * {@code roles.name} of every role assigned to the user, sorted. Display labels only — never
     * input to an authorization decision (auth-and-identity.md §2).
     */
    List<String> roleNamesOf(UserId userId);
}
