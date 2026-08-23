package org.example.auth.idempotency;

import org.example.auth.authorisation.api.CaptureResponse;

/** Cached idempotency value for capture requests including request fingerprint validation data. */
public record CachedCaptureResponse(String requestFingerprint, CaptureResponse response)
    implements CachedIdempotentResponse<CaptureResponse> {}

