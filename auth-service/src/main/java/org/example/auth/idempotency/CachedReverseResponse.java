package org.example.auth.idempotency;

import org.example.auth.authorisation.api.ReverseResponse;

/** Cached idempotency value for reverse requests including request fingerprint validation data. */
public record CachedReverseResponse(String requestFingerprint, ReverseResponse response)
    implements CachedIdempotentResponse<ReverseResponse> {}

