package com.unihub.user.application.port.out;

import java.util.List;
import java.util.UUID;

/** The quarantine store, {@code legacy_sync_errors} (user.md §6). */
public interface LegacySyncErrors {

    void quarantine(UUID runId, List<QuarantinedRow> rows);
}
