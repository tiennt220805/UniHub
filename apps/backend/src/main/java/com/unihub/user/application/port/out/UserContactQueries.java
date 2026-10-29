package com.unihub.user.application.port.out;

import com.unihub.shared.domain.id.UserId;
import com.unihub.user.api.UserContact;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Read-only projection of a user's contact details (backend-coding-standards.md §9.1). */
public interface UserContactQueries {

    Optional<UserContact> findById(UserId id);

    /** Users that exist among {@code ids}, keyed by id. */
    Map<UserId, UserContact> findAllById(Collection<UserId> ids);
}
