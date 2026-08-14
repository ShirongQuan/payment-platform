package org.example.fraud.exception;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
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
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }
}
