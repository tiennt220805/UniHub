/** The device's one cached verification key: the public EC P-256 point of `GET /checkin/verification-key`. */
export type VerificationKey = { readonly x: string; readonly y: string }
