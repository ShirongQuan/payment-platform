package org.example.fraud.exception;

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
@RestControllerAdvice
public class FraudExceptionHandler {

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflictException(
      IdempotencyConflictException e, WebRequest request) {
    return conflict(e, request);
  }

  @ExceptionHandler(FraudEvaluationInProgressException.class)
  public ProblemDetail handleFraudEvaluationInProgressException(
      FraudEvaluationInProgressException e, WebRequest request) {
    return conflict(e, request);
  }

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
