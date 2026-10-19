package com.unihub.shared.application.usecase;

import com.unihub.shared.application.port.in.PurgeEventPublicationsUseCase;
import com.unihub.shared.application.port.out.EventPublicationCleanup;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PurgeEventPublications implements PurgeEventPublicationsUseCase {

    private final EventPublicationCleanup rows;
    private final Clock clock;

    PurgeEventPublications(EventPublicationCleanup rows, Clock clock) {
        this.rows = rows;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int purge(Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        return rows.deletePublishedBefore(clock.instant().minus(retention));
    }
}
