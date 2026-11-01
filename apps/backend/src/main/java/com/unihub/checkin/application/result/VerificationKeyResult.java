package com.unihub.checkin.application.result;

import java.util.Objects;

/**
 * The ticket verification key as the four public JWK members the contract promises (checkin.md §6):
 * nothing that could sign, and no {@code kid}/{@code use}/{@code alg}.
 */
public record VerificationKeyResult(String kty, String crv, String x, String y) {

    public VerificationKeyResult {
        Objects.requireNonNull(kty, "kty");
        Objects.requireNonNull(crv, "crv");
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
    }
}
