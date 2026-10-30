package com.unihub;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.unihub.checkin.archfixture.SignerUser;
import com.unihub.registration.api.archfixture.JoseInApi;
import com.unihub.registration.archfixture.EcKeyOutsideCrypto;
import com.unihub.registration.archfixture.InsideSignerUser;
import com.unihub.registration.infrastructure.out.crypto.FixtureEcKeyUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mutation of the ticket-key structural rules (Phase 5 checklist D47, AC47.1): each rule of
 * {@link ArchitectureTest} turns red on a class that breaks it and stays green on one that does
 * not. Without this the rules would only prove that the code is clean today. The offending classes
 * are fixtures in the test source set, which the production ArchUnit suites do not import.
 */
class TicketKeyRulesTest {

    @Test
    @DisplayName("a class of another module that names TicketSigner is caught")
    void anOutsiderNamingTheSigner_isCaught() {
        var classes = new ClassFileImporter().importClasses(SignerUser.class);

        assertThatThrownBy(
                        () ->
                                ArchitectureTest.only_registration_depends_on_ticket_signing.check(
                                        classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(SignerUser.class.getName())
                .hasMessageContaining("TicketSigner");
    }

    @Test
    @DisplayName("the same rule accepts a Registration class that names TicketSigner")
    void aRegistrationClassNamingTheSigner_isAccepted() {
        var classes = new ClassFileImporter().importClasses(InsideSignerUser.class);

        assertThatCode(
                        () ->
                                ArchitectureTest.only_registration_depends_on_ticket_signing.check(
                                        classes))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an EC key held outside registration.infrastructure.out.crypto is caught")
    void anEcKeyOutsideTheCryptoPackage_isCaught() {
        var classes = new ClassFileImporter().importClasses(EcKeyOutsideCrypto.class);

        assertThatThrownBy(
                        () ->
                                ArchitectureTest.private_ticket_key_types_stay_in_the_crypto_package
                                        .check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(EcKeyOutsideCrypto.class.getName())
                .hasMessageContaining("ECKey");
    }

    @Test
    @DisplayName("an EC key held inside the crypto package is accepted")
    void anEcKeyInsideTheCryptoPackage_isAccepted() {
        var classes = new ClassFileImporter().importClasses(FixtureEcKeyUser.class);

        assertThatCode(
                        () ->
                                ArchitectureTest.private_ticket_key_types_stay_in_the_crypto_package
                                        .check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a JOSE key type in registration.api is caught")
    void aJoseTypeInTheApiPackage_isCaught() {
        var classes = new ClassFileImporter().importClasses(JoseInApi.class);

        assertThatThrownBy(
                        () ->
                                ArchitectureTest.registration_api_depends_on_no_jose_library.check(
                                        classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(JoseInApi.class.getName());
    }
}
