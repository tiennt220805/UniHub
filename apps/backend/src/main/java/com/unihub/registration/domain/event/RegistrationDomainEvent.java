package com.unihub.registration.domain.event;

/**
 * Something that happened to a Registration; translated to outbox messages before it leaves (§8.1).
 */
public sealed interface RegistrationDomainEvent permits RegistrationConfirmed, SeatReserved {}
