package com.unihub.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.unihub.payment.domain.model.Payment;
import com.unihub.shared.application.port.out.AuditEntry;
import com.unihub.shared.application.port.out.AuditTargets;
import com.unihub.shared.application.port.out.AuditTrail;
import com.unihub.shared.security.Actor;
import org.mockito.ArgumentCaptor;

/** What the Payment use-case tests check about the audit trail (checklist D6, AC6.1, AC6.3). */
final class AuditAssertions {
    private AuditAssertions() {}

    /** The one entry recorded; fails if there is none or more than one. */
    static AuditEntry onlyEntry(AuditTrail audit) {
        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        return entry.getValue();
    }

    static void assertRecorded(
            AuditEntry entry, String action, Payment payment, java.time.Instant at) {
        assertThat(entry.action()).isEqualTo(action);
        assertThat(entry.targetType()).isEqualTo(AuditTargets.PAYMENT);
        assertThat(entry.targetId()).isEqualTo(payment.id().value());
        assertThat(entry.occurredAt()).isEqualTo(at);
        assertThat(entry.metadata())
                .containsEntry("registrationId", payment.registrationId().value().toString())
                .containsEntry("amount", payment.amount().toPlainString())
                .containsEntry("currency", payment.currency());
    }

    /** A process, never a user: AC6.3. */
    static void assertSystemActor(AuditEntry entry, String process) {
        assertThat(entry.actor()).isInstanceOf(Actor.SystemActor.class);
        assertThat(((Actor.SystemActor) entry.actor()).process()).isEqualTo(process);
    }
}
