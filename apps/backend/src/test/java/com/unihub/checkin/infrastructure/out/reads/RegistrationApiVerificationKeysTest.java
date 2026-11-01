package com.unihub.checkin.infrastructure.out.reads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.checkin.application.result.VerificationKeyResult;
import com.unihub.registration.api.PublicTicketKey;
import com.unihub.registration.api.TicketVerificationKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Translation only: Registration's four public members become Check-in's, unchanged. */
class RegistrationApiVerificationKeysTest {

    @Test
    @DisplayName("the four public members cross the boundary as they are")
    void translatesTheFourMembers() {
        var registration = mock(TicketVerificationKey.class);
        when(registration.current()).thenReturn(new PublicTicketKey("EC", "P-256", "AA", "BB"));

        var key = new RegistrationApiVerificationKeys(registration).current();

        assertThat(key).isEqualTo(new VerificationKeyResult("EC", "P-256", "AA", "BB"));
    }
}
