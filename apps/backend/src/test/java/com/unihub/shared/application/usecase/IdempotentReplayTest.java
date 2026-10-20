package com.unihub.shared.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unihub.shared.application.port.out.IdempotencyKeyInUseException;
import com.unihub.shared.application.port.out.IdempotencyKeyReusedException;
import com.unihub.shared.application.port.out.IdempotencyRecord;
import com.unihub.shared.application.port.out.IdempotencyScope;
import com.unihub.shared.application.port.out.IdempotencyStore;
import com.unihub.shared.application.port.out.StoredResult;
import com.unihub.shared.domain.id.UserId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four rows of backend-coding-standards.md §21 rule 4. */
class IdempotentReplayTest {
    private static final IdempotencyScope SCOPE = IdempotencyScope.REGISTRATION_CREATE;
    private static final UserId ACTOR = UserId.newId();
    private static final String KEY = "6c1f7e2a-0b3d-4a9e-9b1a-2f6e6b8c9d10";
    private static final String SAME = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    private sealed interface Outcome permits Done {}

    private record Done(String note) implements Outcome {}

    private final IdempotencyStore store = mock(IdempotencyStore.class);

    private Optional<Outcome> replay() {
        return IdempotentReplay.of(store, SCOPE, ACTOR, KEY, SAME, Outcome.class);
    }

    @Test
    @DisplayName("no record: empty, the operation runs")
    void noRecord_isEmpty() {
        when(store.find(SCOPE, ACTOR, KEY)).thenReturn(Optional.empty());

        assertThat(replay()).isEmpty();
        verify(store, never())
                .decode(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a completed record for the same body is decoded and returned")
    void completedSameBody_isReplayed() {
        var stored = new StoredResult("Done", "{\"note\":\"x\"}");
        when(store.find(SCOPE, ACTOR, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord.Completed(SAME, stored)));
        when(store.decode(stored, Outcome.class)).thenReturn(new Done("x"));

        assertThat(replay()).contains(new Done("x"));
    }

    @Test
    @DisplayName("a record made for another body is 422, completed or still in progress")
    void otherBody_isReused() {
        when(store.find(SCOPE, ACTOR, KEY))
                .thenReturn(
                        Optional.of(
                                new IdempotencyRecord.Completed(
                                        OTHER, new StoredResult("Done", "{}"))));
        assertThatThrownBy(this::replay).isInstanceOf(IdempotencyKeyReusedException.class);

        when(store.find(SCOPE, ACTOR, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord.InProgress(OTHER)));
        assertThatThrownBy(this::replay).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("an in-progress record for the same body is 409 in use")
    void inProgressSameBody_isInUse() {
        when(store.find(SCOPE, ACTOR, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord.InProgress(SAME)));

        assertThatThrownBy(this::replay).isInstanceOf(IdempotencyKeyInUseException.class);
    }
}
