package org.example.auth.common.exception;

import org.example.shared.error.ProblemDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler for the auth-service.
 *
 * <p>Translates domain and validation exceptions into RFC 7807 {@link ProblemDetail} responses with
 * appropriate HTTP status codes. Each handler adds context-specific properties (e.g. the offending
 * currency code or account ID) to help API consumers diagnose errors.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

  /** Returns 400 with the rejected currency code when a non-ISO-4217 code is supplied. */
  @ExceptionHandler(InvalidCurrencyException.class)
  public ProblemDetail handleInvalidCurrencyException(
      InvalidCurrencyException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.BAD_REQUEST,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request,
        "currencyCode",
        e.getCurrencyCode());
  }

  /** Returns 400 when a deposit or operation currency does not match the account's currency. */
  @ExceptionHandler(CurrencyMismatchException.class)
  public ProblemDetail handleCurrencyMismatchException(
      CurrencyMismatchException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.BAD_REQUEST,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request,
        "expectedCurrency",
        e.getExpected(),
        "providedCurrency",
        e.getProvided());
  }

  /** Returns 400 when a requested amount exceeds the account's available balance. */
  @ExceptionHandler(InsufficientFundException.class)
  public ProblemDetail handleInsufficientFundException(
      InsufficientFundException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.BAD_REQUEST,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request,
        "availableAmount",
        e.getAvailableAmount(),
        "requestedAmount",
        e.getRequestedAmount());
  }

  /** Returns 404 when no account exists for the requested ID. */
  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail handleAccountNotFoundException(
      AccountNotFoundException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.NOT_FOUND,
        "Account not found",
        e.getMessage(),
        e.getErrorCode().name(),
        request,
        "accountId",
        e.getAccountId());
  }

  @ExceptionHandler(AuthorisationNotFoundException.class)
  public ProblemDetail handleAuthorisationNotFoundException(
      AuthorisationNotFoundException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.NOT_FOUND,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request,
        "authorisationId",
        e.getAuthorisationId());
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflictException(
      IdempotencyConflictException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.CONFLICT,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request);
  }

  @ExceptionHandler(AuthorisationIllegalStateException.class)
  public ProblemDetail handleAuthorisationIllegalStateException(
      AuthorisationIllegalStateException e, WebRequest request) {
    return ProblemDetails.from(
        HttpStatus.CONFLICT,
        e.getErrorCode().getDefaultMessage(),
        e.getMessage(),
        e.getErrorCode().name(),
        request);
  }
}
