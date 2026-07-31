package org.example.auth.authorisation.application;

import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.common.validation.ValidationHelpers;
import org.example.auth.outbox.domain.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service orchestrating authorisation lifecycle use-cases.
 *
 * <p>Handles currency normalization, delegates transactional mutations, and resolves idempotency
 * races when concurrent requests insert the same key.
 */
@Slf4j
@Service
public class AuthorisationServiceImpl implements AuthorisationService {
  private final AuthorisationTransactionalExecutor authorisationTransactionalExecutor;
  private final AuthorisationRepository authorisationRepository;
  private final AuthorisationEventRepository authorisationEventRepository;

  public AuthorisationServiceImpl(
      AuthorisationTransactionalExecutor authorisationTransactionalExecutor,
      AuthorisationRepository authorisationRepository,
      AuthorisationEventRepository authorisationEventRepository) {
    this.authorisationTransactionalExecutor = authorisationTransactionalExecutor;
    this.authorisationRepository = authorisationRepository;
    this.authorisationEventRepository = authorisationEventRepository;
  }

  @Override
  public AuthorisationResponse authorise(AuthorisationRequest request) {
    String normalizedCurrency =
        ValidationHelpers.normalizeAndValidateCurrency(request.currencyCode());
    log.debug(
        "Handling authorise request, accountId={}, idempotencyKey={}, normalizedCurrency={}",
        request.accountId(),
        request.idempotencyKey(),
        normalizedCurrency);
    try {
      return authorisationTransactionalExecutor.authoriseInTransaction(request, normalizedCurrency);
    } catch (ConcurrentIdempotencyRaceException e) {
      log.warn(
          "Resolving authorise idempotency after concurrent race, accountId={}, idempotencyKey={}",
          request.accountId(),
          request.idempotencyKey(),
          e);
      return resolveIdempotencyAfterRollback(request, normalizedCurrency);
    }
  }

  private AuthorisationResponse resolveIdempotencyAfterRollback(
      AuthorisationRequest request, String normalizedCurrency) {
    return authorisationRepository
        .findByAccountIdAndIdempotencyKey(request.accountId(), request.idempotencyKey())
        .map(entity -> validateAndBuildIdempotentResponse(entity, request, normalizedCurrency))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Duplicate idempotency key detected but no existing authorisation was found"));
  }

  private AuthorisationResponse validateAndBuildIdempotentResponse(
      AuthorisationEntity entity, AuthorisationRequest request, String normalizedCurrency) {
    if (isSameIdempotentRequest(entity, request, normalizedCurrency)) {
      return new AuthorisationResponse(
          entity.getId(),
          entity.getAccountId(),
          request.idempotencyKey(),
          entity.getAmount(),
          entity.getCurrencyCode(),
          entity.getMerchantReference(),
          entity.getStatus(),
          entity.getCreatedAt(),
          entity.getUpdatedAt());
    }
    throw new IdempotencyConflictException();
  }

  private boolean isSameIdempotentRequest(
      AuthorisationEntity entity, AuthorisationRequest request, String normalizedCurrency) {
    return entity.getAmount().compareTo(request.amount()) == 0
        && entity.getCurrencyCode().equals(normalizedCurrency)
        && Objects.equals(entity.getMerchantReference(), request.merchantReference());
  }

  @Override
  @Transactional(readOnly = true)
  public AuthorisationResponse getAuthorisationById(UUID authorisationId) {
    log.debug("Fetching authorisation by id, authorisationId={}", authorisationId);
    AuthorisationEntity authorisation =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));
    return new AuthorisationResponse(
        authorisation.getId(),
        authorisation.getAccountId(),
        null,
        authorisation.getAmount(),
        authorisation.getCurrencyCode(),
        authorisation.getMerchantReference(),
        authorisation.getStatus(),
        authorisation.getCreatedAt(),
        authorisation.getUpdatedAt());
  }

  @Override
  @Transactional
  public CaptureResponse capture(UUID authorisationId, CaptureRequest captureRequest) {
    log.debug(
        "Handling capture request, authorisationId={}, idempotencyKey={}",
        authorisationId,
        captureRequest.idempotencyKey());
    try {
      return authorisationTransactionalExecutor.captureInTransaction(
          authorisationId, captureRequest);
    } catch (ConcurrentIdempotencyRaceException e) {
      log.warn(
          "Resolving capture idempotency after concurrent race, authorisationId={}, idempotencyKey={}",
          authorisationId,
          captureRequest.idempotencyKey(),
          e);
      return resolveIdempotencyAfterCaptureRollback(authorisationId, captureRequest);
    }
  }

  private CaptureResponse resolveIdempotencyAfterCaptureRollback(
      UUID authorisationId, CaptureRequest captureRequest) {
    // load authorisation by id, fail if not found
    AuthorisationEntity authorisationEntity =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));

    // if capture idempotency key matches → return previous response
    // else → throw already captured / conflict
    return authorisationEventRepository
        .findByAccountIdAndEventTypeAndIdempotencyKey(
            authorisationEntity.getAccountId(),
            captureRequest.idempotencyKey(),
            EventType.AUTHORISATION_CAPTURED.toString())
        .map(
            ignored ->
                new CaptureResponse(
                    authorisationId,
                    captureRequest.idempotencyKey(),
                    authorisationEntity.getAmount(),
                    authorisationEntity.getCurrencyCode(),
                    AuthorisationStatus.CAPTURED,
                    authorisationEntity.getUpdatedAt()))
        .orElseThrow(IdempotencyConflictException::new);
  }

  @Override
  public ReverseResponse reverse(UUID authorisationId, ReverseRequest reverseRequest) {
    // TODO
    return null;
  }
}
