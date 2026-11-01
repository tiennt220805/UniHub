package com.unihub.checkin.application.port.out;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.checkin.domain.model.ScanKey;
import com.unihub.checkin.domain.model.ScanOutcome;
import com.unihub.shared.domain.id.RegistrationId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence of {@code check_in_scans} (database-schema.md §6.1; Phase 5 checklist D52). Tier A in
 * the standards' sense of a pure core, with JDBC underneath because the guarantees are statements,
 * not an aggregate's version: an advisory lock per registration and {@code ON CONFLICT}. The lock
 * and the writes that follow it must run in one transaction.
 */
public interface CheckInScans {

    /** The stored rows among these ids; an id never seen is absent. */
    Map<ScanId, StoredScan> findByIds(Collection<ScanId> ids);

    /**
     * Stores scans judged invalid as {@code REJECTED_INVALID}; a row may name no registration. An
     * id already stored is left alone ({@code ON CONFLICT (id) DO NOTHING}).
     *
     * @return the rows actually inserted
     */
    int insertRejected(List<NewScan> scans);

    /**
     * Stores scans judged valid, not yet decided: the caller resolves the registration in the same
     * transaction, after {@link #lockRegistration}. Each scan names its registration. An id already
     * stored is left alone.
     *
     * @return the rows actually inserted
     */
    int insertUnresolved(List<NewScan> scans);

    /**
     * Serializes every reconciliation of this registration, across batches, devices and instances,
     * until the current transaction ends: {@code pg_advisory_xact_lock}. Postgres, never Redis, so
     * sync keeps working with Redis down.
     *
     * @throws IllegalStateException when no transaction is active (the lock would be released at
     *     once)
     */
    void lockRegistration(RegistrationId registration);

    /**
     * The registration's scans judged valid, which is every stored scan not {@code
     * REJECTED_INVALID}.
     */
    List<ScanKey> findValidScans(RegistrationId registration);

    /**
     * Writes the outcomes that differ from the stored ones; {@code updated_at} moves only for
     * those. A scan stored {@code REJECTED_INVALID} is never changed.
     */
    void updateOutcomes(Map<ScanId, ScanOutcome> outcomes);
}
