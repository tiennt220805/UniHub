package com.unihub.registration.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.unihub.UniHubApplication;
import com.unihub.registration.infrastructure.out.crypto.TicketSigningKey;
import com.unihub.registration.infrastructure.out.crypto.TicketTestKeys;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The ticket signing key is required configuration (Phase 5 checklist D49, AC49.1): without a
 * usable one the application stops at start with a message that says what to set, rather than
 * issuing tickets no device can verify. The same rule as Redis (D21) and SMTP (D36), with one
 * addition they do not need: the value is a private key, so no failure may print it, in the
 * exception or in Boot's own startup report.
 */
@ExtendWith(OutputCaptureExtension.class)
class TicketKeyRequiredStartupIT extends IntegrationTestSupport {

    private static final String PROPERTY = "unihub.ticket.signing-key";

    private SpringApplicationBuilder application() {
        // command-line arguments outrank the system property IntegrationTestSupport sets
        return new SpringApplicationBuilder(UniHubApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "unihub.security.jwt.secret=" + TestJwts.SECRET);
    }

    @Test
    @DisplayName("AC49.1: a blank key stops the application, and the message says what to set")
    void blankKey_stopsTheApplication() {
        assertThatThrownBy(() -> application().run("--" + PROPERTY + "="))
                .hasStackTraceContaining("unihub.ticket.signing-key is invalid: it is not set")
                .hasStackTraceContaining("UNIHUB_TICKET_SIGNING_KEY")
                .hasStackTraceContaining("generate-ticket-key.sh");
    }

    @Test
    @DisplayName("AC49.1: text that is not a JWK stops the application")
    void notAJwk_stopsTheApplication() {
        assertThatThrownBy(() -> application().run("--" + PROPERTY + "=definitely-not-a-key"))
                .hasStackTraceContaining("it is not a JWK");
    }

    @Test
    @DisplayName("AC49.1: an RSA key stops the application")
    void rsaKey_stopsTheApplication() {
        assertThatThrownBy(
                        () ->
                                application()
                                        .run(
                                                "--"
                                                        + PROPERTY
                                                        + "="
                                                        + TicketTestKeys.newPrivateRsaJwk()))
                .hasStackTraceContaining("not an EC key");
    }

    @Test
    @DisplayName("AC49.1: a public-only P-256 key stops the application")
    void publicOnlyKey_stopsTheApplication() {
        assertThatThrownBy(
                        () ->
                                application()
                                        .run(
                                                "--"
                                                        + PROPERTY
                                                        + "="
                                                        + TicketTestKeys.newPublicOnlyJwk()))
                .hasStackTraceContaining("no private member");
    }

    @Test
    @DisplayName(
            "a P-384 private key stops the application and its private member is never printed")
    void p384PrivateKey_stopsTheApplication_withoutPrintingTheKey(CapturedOutput output) {
        var p384 = TicketTestKeys.newPrivateJwk(Curve.P_384);
        var d = TicketTestKeys.privateMemberOf(p384);

        assertThatThrownBy(() -> application().run("--" + PROPERTY + "=" + p384))
                .hasStackTraceContaining("not P-256")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(d));

        // Boot reports a failed start to the log as well; that report must not carry the key
        // either.
        assertThat(output.getAll()).contains("not P-256").doesNotContain(d);
    }

    @Test
    @DisplayName(
            "a valid private key starts the application, with a key that does not print itself")
    void validKey_startsTheApplication() {
        var jwk = TicketTestKeys.newPrivateJwk();

        // A full web context (security needs one) on a random port; the rejected cases never get
        // that far.
        try (var context =
                application()
                        .web(WebApplicationType.SERVLET)
                        .run("--" + PROPERTY + "=" + jwk, "--server.port=0")) {
            var key = context.getBean(TicketSigningKey.class);

            assertThat(key.toString()).doesNotContain(TicketTestKeys.privateMemberOf(jwk));
            assertThat(context.getBean(TicketSigningProperties.class).toString())
                    .doesNotContain(TicketTestKeys.privateMemberOf(jwk));
        }
    }

    private static String stackTrace(Throwable throwable) {
        var out = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
