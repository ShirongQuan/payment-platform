package org.example.fraud.exception;

import lombok.extern.slf4j.Slf4j;
import org.example.shared.error.ProblemDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler for the auth-service.
 *
 * <p>Translates domain and validation exceptions into RFC 7807 {@link
 * org.springframework.http.ProblemDetail} responses with appropriate HTTP status codes. Each
 * handler adds context-specific properties (e.g. the offending currency code or account ID) to help
 * API consumers diagnose errors.
 */
@Slf4j
@RestControllerAdvice
public class FraudExceptionHandler {

  /** Client resubmitted the same idempotency key with different request content. */
  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflictException(
      IdempotencyConflictException e, WebRequest request) {
    log.warn("Idempotency conflict while handling fraud request: {}", e.getMessage());
    return conflict(e, request);
  }

  /** A duplicate request arrived while the original evaluation for that key is still PENDING. */
  @ExceptionHandler(FraudEvaluationInProgressException.class)
  public ProblemDetail handleFraudEvaluationInProgressException(
      FraudEvaluationInProgressException e, WebRequest request) {
    log.warn("Fraud evaluation still in progress: {}", e.getMessage());
    return conflict(e, request);
  }

  /** Builds a 409 CONFLICT {@link ProblemDetail} from a {@link CodedException}'s error code/message. */
  private static <T extends RuntimeException & CodedException> ProblemDetail conflict(
      T e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.CONFLICT,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request);
  }
}
