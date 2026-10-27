package com.unihub.shared.domain;

/**
 * Implemented by an exception that says a dependency is down, not that the message is bad: the seat
 * lock could not be taken, the payment gateway was never reached. On a queue that declares it waits
 * for its dependency ({@code Topology}), the error handler keeps such a message in the retry chain
 * at the last stage instead of parking it after three attempts (messaging.md §4, checklist D19,
 * D25). On any other queue the interface changes nothing.
 */
public interface RetryUntilAvailable {}
