package org.example.auth.common.exception;

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
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("currencyCode", e.getCurrencyCode());
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }

  /** Returns 400 when a deposit or operation currency does not match the account's currency. */
  @ExceptionHandler(CurrencyMismatchException.class)
  public ProblemDetail handleCurrencyMismatchException(
      CurrencyMismatchException e, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("expectedCurrency", e.getExpected());
    pd.setProperty("providedCurrency", e.getProvided());
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }

  /** Returns 400 when a requested amount exceeds the account's available balance. */
  @ExceptionHandler(InsufficientFundException.class)
  public ProblemDetail handleInsufficientFundException(
      InsufficientFundException e, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("availableAmount", e.getAvailableAmount());
    pd.setProperty("requestedAmount", e.getRequestedAmount());
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }

  /** Returns 404 when no account exists for the requested ID. */
  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail handleAccountNotFoundException(
      AccountNotFoundException e, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("accountId", e.getAccountId());
    pd.setTitle("Account not found");
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }

  @ExceptionHandler(AuthorisationNotFoundException.class)
  public ProblemDetail handleAuthorisationNotFoundException(
      AuthorisationNotFoundException e, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    pd.setTitle(e.getErrorCode().getDefaultMessage());
    if (request instanceof ServletWebRequest servletWebRequest) {
      pd.setInstance(URI.create(servletWebRequest.getRequest().getRequestURI()));
    }
    pd.setProperty("authorisationId", e.getAuthorisationId());
    pd.setProperty("errorCode", e.getErrorCode().name());
    return pd;
  }

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
