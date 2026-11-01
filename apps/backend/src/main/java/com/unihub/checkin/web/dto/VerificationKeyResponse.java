package com.unihub.checkin.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * packages/api-contract {@code VerificationKeyResponse}: a public JWK, exactly these four members.
 * {@code kty} and {@code crv} are constants in the contract; see {@code CheckInOpenApiConfig}.
 */
@Schema(
        types = {"object"},
        requiredProperties = {"kty", "crv", "x", "y"})
public record VerificationKeyResponse(String kty, String crv, String x, String y) {}
