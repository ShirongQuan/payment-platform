package org.example.auth.idempotency;

/**
 * Common shape for cached idempotency payloads: a request fingerprint used to detect conflicting
 * reuse of the same idempotency key, alongside the cached response itself.
 *
 * <p>Records implementing this interface automatically satisfy it via their canonical accessors
 * (e.g. {@code requestFingerprint()} and {@code response()}), so no extra code is required beyond
 * declaring {@code implements CachedIdempotentResponse<ResponseType>}.
 *
 * @param <R> the type of the cached response payload
 */
public interface CachedIdempotentResponse<R> {
  String requestFingerprint();

  R response();
}

