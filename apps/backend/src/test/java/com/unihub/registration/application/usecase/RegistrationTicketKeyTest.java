package com.unihub.registration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unihub.registration.api.PublicTicketKey;
import com.unihub.registration.application.port.out.TicketSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationTicketKeyTest {

    @Test
    @DisplayName("Check-in gets the signer's public key as plain values, and nothing else")
    void current_isTheSignersPublicKey() {
        var signer = mock(TicketSigner.class);
        var published = new PublicTicketKey("EC", "P-256", "x-coordinate", "y-coordinate");
        when(signer.publicKey()).thenReturn(published);

        assertThat(new RegistrationTicketKey(signer).current()).isSameAs(published);
    }
}
