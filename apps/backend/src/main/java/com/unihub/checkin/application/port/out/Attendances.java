package com.unihub.checkin.application.port.out;

import com.unihub.checkin.domain.model.ScanId;
import com.unihub.shared.domain.id.RegistrationId;
import java.time.Instant;

/** Persistence of {@code attendances}: one row per registration, whose UNIQUE is the guarantee. */
public interface Attendances {

    /**
     * Records the winning scan of the registration, inserting the row or pointing the existing one
     * at the new winner. {@code updated_at} moves only when the winner changes.
     */
    void upsert(RegistrationId registration, ScanId winner, Instant checkedInAt);
}
