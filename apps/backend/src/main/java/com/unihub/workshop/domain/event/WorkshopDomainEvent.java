package com.unihub.workshop.domain.event;

/** Something that happened to a Workshop; translated to outbox messages before it leaves (§8.1). */
public sealed interface WorkshopDomainEvent permits WorkshopCancelled, WorkshopRescheduled {}
