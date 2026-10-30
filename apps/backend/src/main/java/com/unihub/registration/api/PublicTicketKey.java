package com.unihub.registration.api;

/**
 * The public half of the ticket signing key as a JWK (RFC 7517, RFC 7518 §6.2.1): exactly the four
 * members the contract promises ({@code kty}, {@code crv}, {@code x}, {@code y}), never the private
 * member and never {@code kid}/{@code use}/{@code alg}. A plain record so this package depends on
 * no JOSE library.
 */
public record PublicTicketKey(String kty, String crv, String x, String y) {}
