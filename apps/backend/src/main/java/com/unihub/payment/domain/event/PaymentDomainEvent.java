package com.unihub.payment.domain.event;

/** Something that happened to a Payment; translated to outbox messages before it leaves (§8.1). */
public sealed interface PaymentDomainEvent permits PaymentSucceeded, RefundRequired {}
