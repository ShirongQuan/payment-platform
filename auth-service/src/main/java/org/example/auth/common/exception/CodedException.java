package org.example.auth.common.exception;

/**
 * Contract for exceptions that carry a stable, machine-readable {@link ErrorCode}, used by {@link
 * AuthExceptionHandler} to build consistent RFC 7807 problem-detail error responses.
 */
public interface CodedException {
  ErrorCode getErrorCode();
}
