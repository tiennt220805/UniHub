package com.unihub.registration.infrastructure.out.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Makes the {@code ticket_code} (checkin.md §4; Phase 5 checklist D48): 128 random bits written in
 * Crockford base32 without padding. 128 bits is 25.6 base32 digits, so a code is always exactly 26
 * characters, the first holding only the top 3 bits and so never above {@code 7}; leading zeros are
 * kept, otherwise about one code in eight would come out one character short. Unguessable, and
 * readable aloud if one ever has to be typed (Crockford leaves out {@code I L O U}); the {@code
 * UNIQUE} constraint on {@code registrations.ticket_code} is the backstop for a collision.
 */
final class TicketCodeGenerator {

    static final int BITS = 128;
    static final int LENGTH = 26;

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final BigInteger THIRTY_TWO = BigInteger.valueOf(32);

    private final RandomGenerator random;

    TicketCodeGenerator() {
        this(new SecureRandom());
    }

    /**
     * The source of randomness is injected so a test can feed fixed bytes; production is secure.
     */
    TicketCodeGenerator(RandomGenerator random) {
        this.random = random;
    }

    String next() {
        var bytes = new byte[BITS / Byte.SIZE];
        random.nextBytes(bytes);
        return encode(bytes);
    }

    /** The 16 bytes as an unsigned 128-bit number, in 26 base32 digits, most significant first. */
    static String encode(byte[] bytes) {
        if (bytes.length != BITS / Byte.SIZE) {
            throw new IllegalArgumentException("a ticket code is made of 16 bytes");
        }
        var value = new BigInteger(1, bytes);
        var code = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            var divided = value.divideAndRemainder(THIRTY_TWO);
            code[i] = ALPHABET.charAt(divided[1].intValue());
            value = divided[0];
        }
        return new String(code);
    }
}
