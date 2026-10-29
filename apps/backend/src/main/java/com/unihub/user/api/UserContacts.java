package com.unihub.user.api;

import com.unihub.shared.domain.id.UserId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * User's answer to "who is this, and where do I write to them?" for Notification (Phase 4 checklist
 * D35; backend-coding-standards.md §7, interaction type 1: a read for display). Each call is its
 * own read at the Master, not inside the caller's transaction: the consumer runs right after the
 * write that produced the event, and a lagging Replica could still miss a new account.
 */
public interface UserContacts {

    /** Empty when no such user exists. */
    Optional<UserContact> find(UserId id);

    /**
     * One read for a whole fan-out. Ids with no user are absent from the result, which is how the
     * caller tells "skip this recipient" from a failure.
     */
    Map<UserId, UserContact> findAll(Collection<UserId> ids);
}
