package com.unihub.user.api;

import com.unihub.shared.domain.id.UserId;

/**
 * How to address a user in a message: the name to greet and the email to send to. Nothing else of
 * the account leaves User (no status, no roles, no legacy id).
 */
public record UserContact(UserId id, String fullName, String email) {}
