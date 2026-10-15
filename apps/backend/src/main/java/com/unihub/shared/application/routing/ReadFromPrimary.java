package com.unihub.shared.application.routing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sends a {@code @Transactional(readOnly = true)} use case to the Master instead of the Replica
 * (backend-coding-standards.md §20, INV-03). Use it for consistency-sensitive reads only:
 * idempotency lookups and read-after-own-write views. A read-write transaction always reads the
 * Master and does not need it.
 *
 * <p>Put it on the method (or class) whose call starts the transaction, called through the Spring
 * proxy — like {@code @Transactional}, a {@code this.} call bypasses it, and a transaction that has
 * already taken its connection keeps that connection.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadFromPrimary {}
