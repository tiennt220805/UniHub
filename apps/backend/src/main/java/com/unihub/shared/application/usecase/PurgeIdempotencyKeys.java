package com.unihub.shared.application.usecase;

import com.unihub.shared.application.port.in.PurgeIdempotencyKeysUseCase;
import com.unihub.shared.application.port.out.IdempotencyKeyCleanup;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PurgeIdempotencyKeys implements PurgeIdempotencyKeysUseCase {

    private final IdempotencyKeyCleanup keys;
    private final Clock clock;

    PurgeIdempotencyKeys(IdempotencyKeyCleanup keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int purge(Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        return keys.deleteCompletedBefore(clock.instant().minus(retention));
    }
}
