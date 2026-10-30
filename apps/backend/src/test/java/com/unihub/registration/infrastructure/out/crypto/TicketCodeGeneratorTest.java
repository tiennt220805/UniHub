package com.unihub.registration.infrastructure.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code ticket_code} format of Phase 5 checklist D48: 128 bits, Crockford base32, 26
 * characters.
 */
class TicketCodeGeneratorTest {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    @DisplayName("all-zero bytes encode to 26 zeros: leading zeros are kept")
    void allZeroBytes_keepTheirLeadingZeros() {
        assertThat(TicketCodeGenerator.encode(new byte[16])).isEqualTo("0".repeat(26));
    }

    @Test
    @DisplayName("all-ones bytes encode to 7 and 25 Z: the first digit holds only the top 3 bits")
    void allOnesBytes_fillTheTopDigitWithThreeBits() {
        var ones = new byte[16];
        Arrays.fill(ones, (byte) 0xFF);

        assertThat(TicketCodeGenerator.encode(ones)).isEqualTo("7" + "Z".repeat(25));
    }

    @Test
    @DisplayName("the encoding is positional and most significant digit first")
    void encoding_isPositional() {
        var one = new byte[16];
        one[15] = 1;
        var thirtyTwo = new byte[16];
        thirtyTwo[15] = 32;

        assertThat(TicketCodeGenerator.encode(one)).isEqualTo("0".repeat(25) + "1");
        assertThat(TicketCodeGenerator.encode(thirtyTwo)).isEqualTo("0".repeat(24) + "10");
    }

    @Test
    @DisplayName("anything but 16 bytes is refused")
    void wrongLength_isRefused() {
        assertThatThrownBy(() -> TicketCodeGenerator.encode(new byte[15]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketCodeGenerator.encode(new byte[17]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "every generated code is 26 characters of the Crockford alphabet, first one at most 7")
    void generatedCodes_haveTheDocumentedShape() {
        var generator = new TicketCodeGenerator();

        for (int i = 0; i < 2_000; i++) {
            var code = generator.next();

            assertThat(code).hasSize(26);
            assertThat(code).matches("[" + ALPHABET + "]{26}");
            assertThat(code.charAt(0)).isLessThanOrEqualTo('7');
        }
    }

    @Test
    @DisplayName("AC48.2: a thousand codes are a thousand different codes")
    void thousandCodes_areDistinct() {
        var generator = new TicketCodeGenerator();
        var seen = new HashSet<String>();

        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.next());
        }

        assertThat(seen).hasSize(1_000);
    }

    @Test
    @DisplayName("the code comes from the injected source of randomness, nothing else")
    void code_comesFromTheInjectedRandomness() {
        var fixed = new TicketCodeGenerator(new FixedBytes((byte) 0));

        assertThat(fixed.next()).isEqualTo("0".repeat(26));
    }

    private record FixedBytes(byte value) implements java.util.random.RandomGenerator {
        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, value);
        }
    }
}
