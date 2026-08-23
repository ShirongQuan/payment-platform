package org.example.auth.idempotency;

import org.example.auth.authorisation.api.AuthorisationResponse;

/** Cached idempotency value for authorise requests including request fingerprint validation data. */
public record CachedAuthorisationResponse(String requestFingerprint, AuthorisationResponse response)
    implements CachedIdempotentResponse<AuthorisationResponse> {}
