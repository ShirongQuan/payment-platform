package org.example.auth.authorisation.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.OperationType;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.common.validation.ValidationHelpers;
import org.example.auth.fraud.FraudDecision;
import org.example.auth.fraud.FraudOrchestrator;
import org.example.auth.idempotency.CachedAuthorisationResponse;
import org.example.auth.idempotency.CachedCaptureResponse;
import org.example.auth.idempotency.CachedIdempotentResponse;
import org.example.auth.idempotency.CachedReverseResponse;
import org.example.auth.idempotency.IdempotencyProperties;
import org.example.auth.idempotency.IdempotencyService;
import org.example.auth.outbox.domain.EventType;
import org.example.shared.correlation.CorrelationIdResolver;
import org.example.shared.idempotency.RequestHashing;
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
@RequiredArgsConstructor
public class AuthorisationServiceImpl implements AuthorisationService {
  private final AuthorisationTransactionalExecutor authorisationTransactionalExecutor;
  private final AuthorisationRepository authorisationRepository;
  private final AuthorisationEventRepository authorisationEventRepository;
  private final AccountRepository accountRepository;
  private final FraudOrchestrator fraudOrchestrator;
  private final CorrelationIdResolver correlationIdResolver;
  private final IdempotencyProperties idempotencyProperties;
  private final IdempotencyService idempotencyService;

  @Override
  public AuthorisationResponse authorise(AuthorisationRequest request, String clientIpAddress) {
    String normalizedCurrency =
        ValidationHelpers.normalizeAndValidateCurrency(request.currencyCode());
    log.debug(
        "Handling authorise request, accountId={}, idempotencyKey={}, normalizedCurrency={}, clientIpAddress={}",
        request.accountId(),
        request.idempotencyKey(),
        normalizedCurrency,
        clientIpAddress);

    // if the authorisation response is already in the cache, just return it
    String expectedFingerprint = buildAuthoriseRequestFingerprint(request, normalizedCurrency);
    Optional<AuthorisationResponse> authorisationResponseOpt =
        getCachedResponse(
            OperationType.AUTHORISE,
            request.accountId(),
            request.idempotencyKey(),
            CachedAuthorisationResponse.class,
            expectedFingerprint);
    if (authorisationResponseOpt.isPresent()) {
      log.debug(
          "Found authorisationResponse in cache for operation=authorise, accountId={}, idempotencyKey={}",
          request.accountId(),
          request.idempotencyKey());
      return authorisationResponseOpt.get();
    }

    // Best-effort DB idempotency pre-check, before paying for a fraud-service call. This is an
    // optimistic fast-path only (a concurrent duplicate can still race past it); the authoritative
    // check-and-insert happens inside authorisationTransactionalExecutor.authoriseInTransaction.
    // Doing this here avoids an unnecessary external fraud-service round trip (and, with it, a
    // doomed-to-replay DB transaction) on a cache-cold retry of an already-processed request.
    Optional<AuthorisationEntity> existingEntityOpt =
        authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            request.accountId(), request.idempotencyKey());
    if (existingEntityOpt.isPresent()) {
      log.debug(
          "Found existing authorisation in DB pre-check for operation=authorise, accountId={}, idempotencyKey={}",
          request.accountId(),
          request.idempotencyKey());
      validateAndBuildIdempotentResponse(existingEntityOpt.get(), request, normalizedCurrency);
    }

    UUID correlationId = correlationIdResolver.resolveOrCreate();

    // Account-active gate: only call fraud-service if the account is currently ACTIVE. This both
    // saves an external call for accounts we already know will be declined, and ensures a locked
    // account can never slip through to authorisation just because fraud-service was unavailable.
    AccountEntity accountEntity =
        accountRepository
            .findById(request.accountId())
            .orElseThrow(() -> new AccountNotFoundException(request.accountId()));

    PreAuthDecision preAuthDecision;
    if (accountEntity.getStatus() != AccountStatus.ACTIVE) {
      log.warn(
          "Skipping fraud check because account is not active, accountId={}, idempotencyKey={}, status={}",
          request.accountId(),
          request.idempotencyKey(),
          accountEntity.getStatus());
      preAuthDecision = new PreAuthDecision.AccountNotActive(accountEntity.getStatus());
    } else {
      FraudDecision fraudDecision =
          fraudOrchestrator.evaluate(request, normalizedCurrency, clientIpAddress);
      log.debug(
          "Fraud decision evaluated, accountId={}, idempotencyKey={}, fraudDecision={}",
          request.accountId(),
          request.idempotencyKey(),
          fraudDecision.toString());
      preAuthDecision = new PreAuthDecision.FraudEvaluated(fraudDecision);
    }

    try {
      AuthorisationResponse authorisationResponse =
          authorisationTransactionalExecutor.authoriseInTransaction(
              request, normalizedCurrency, preAuthDecision, correlationId);
      saveResponseToCache(
          OperationType.AUTHORISE,
          request.accountId(),
          request.idempotencyKey(),
          new CachedAuthorisationResponse(expectedFingerprint, authorisationResponse));
      return authorisationResponse;
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
        .findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            request.accountId(), request.idempotencyKey())
        .map(entity -> validateAndBuildIdempotentResponse(entity, request, normalizedCurrency))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Duplicate idempotency key detected but no existing authorisation was found"));
  }

  private AuthorisationResponse validateAndBuildIdempotentResponse(
      AuthorisationEntity entity, AuthorisationRequest request, String normalizedCurrency) {
    if (isSameIdempotentRequest(entity, request, normalizedCurrency)) {
      AuthorisationResponse authorisationResponse =
          new AuthorisationResponse(
              entity.getId(),
              entity.getAccountId(),
              request.idempotencyKey(),
              entity.getAmount(),
              entity.getCurrencyCode(),
              entity.getMerchantReference(),
              entity.getStatus(),
              entity.getCreatedAt(),
              entity.getUpdatedAt());
      saveResponseToCache(
          OperationType.AUTHORISE,
          entity.getAccountId(),
          request.idempotencyKey(),
          new CachedAuthorisationResponse(
              buildAuthoriseRequestFingerprint(request, normalizedCurrency),
              authorisationResponse));
      return authorisationResponse;
    }
    throw new IdempotencyConflictException();
  }

  /**
   * Generic best-effort read for a cached idempotency response. Returns {@link Optional#empty()} on
   * a cache miss or on any cache read failure (Redis unavailable, deserialization error, etc.) so
   * callers fall back to the normal transactional path. Throws {@link IdempotencyConflictException}
   * when a cached entry exists but its request fingerprint doesn't match the current request, since
   * that is a genuine business conflict rather than a caching concern.
   */
  private <C extends CachedIdempotentResponse<R>, R> Optional<R> getCachedResponse(
      OperationType operationType,
      UUID scopeId,
      String idempotencyKey,
      Class<C> cacheType,
      String expectedFingerprint) {
    Optional<C> cachedOpt;
    try {
      cachedOpt = idempotencyService.get(operationType, scopeId, idempotencyKey, cacheType);
    } catch (Exception e) {
      log.warn(
          "Failed to read idempotency cache entry, operationType={}, scopeId={}, idempotencyKey={}",
          operationType,
          scopeId,
          idempotencyKey,
          e);
      return Optional.empty();
    }

    if (cachedOpt.isEmpty()) {
      return Optional.empty();
    }

    C cached = cachedOpt.get();
    String cachedFingerprint = cached.requestFingerprint();
    R cachedResponse = cached.response();
    if (cachedFingerprint == null || cachedResponse == null) {
      // Backward compatibility for old cache payloads that may not include fingerprint wrapper.
      return Optional.empty();
    }

    if (!Objects.equals(cachedFingerprint, expectedFingerprint)) {
      throw new IdempotencyConflictException();
    }

    return Optional.of(cachedResponse);
  }

  /**
   * Generic best-effort store of a cached idempotency response; failures are logged and swallowed.
   */
  private void saveResponseToCache(
      OperationType operationType,
      UUID scopeId,
      String idempotencyKey,
      CachedIdempotentResponse<?> cachedPayload) {
    try {
      idempotencyService.store(
          operationType, scopeId, idempotencyKey, cachedPayload, idempotencyProperties.ttl());
    } catch (Exception e) {
      log.warn(
          "Failed to store idempotency cache entry, operationType={}, scopeId={}, idempotencyKey={}",
          operationType,
          scopeId,
          idempotencyKey,
          e);
    }
  }

  private String buildAuthoriseRequestFingerprint(
      AuthorisationRequest request, String normalizedCurrency) {
    String canonicalRequest =
        RequestHashing.canonicalJoin(
            request.accountId().toString(),
            request.amount().stripTrailingZeros().toPlainString(),
            normalizedCurrency,
            request.merchantReference());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
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
  public CaptureResponse capture(UUID authorisationId, CaptureRequest captureRequest) {
    log.debug(
        "Handling capture request, authorisationId={}, idempotencyKey={}",
        authorisationId,
        captureRequest.idempotencyKey());

    String expectedFingerprint = buildCaptureRequestFingerprint(authorisationId);
    Optional<CaptureResponse> cachedResponseOpt =
        getCachedResponse(
            OperationType.CAPTURE,
            authorisationId,
            captureRequest.idempotencyKey(),
            CachedCaptureResponse.class,
            expectedFingerprint);
    if (cachedResponseOpt.isPresent()) {
      log.debug(
          "Found captureResponse in cache for operation=capture, authorisationId={}, idempotencyKey={}",
          authorisationId,
          captureRequest.idempotencyKey());
      return cachedResponseOpt.get();
    }

    UUID correlationId = correlationIdResolver.resolveOrCreate();
    try {
      CaptureResponse captureResponse =
          authorisationTransactionalExecutor.captureInTransaction(
              authorisationId, captureRequest, correlationId);
      saveResponseToCache(
          OperationType.CAPTURE,
          authorisationId,
          captureRequest.idempotencyKey(),
          new CachedCaptureResponse(expectedFingerprint, captureResponse));
      return captureResponse;
    } catch (ConcurrentIdempotencyRaceException e) {
      log.warn(
          "Resolving capture idempotency after concurrent race, authorisationId={}, idempotencyKey={}",
          authorisationId,
          captureRequest.idempotencyKey(),
          e);
      CaptureResponse resolvedResponse =
          resolveIdempotencyAfterCaptureRollback(authorisationId, captureRequest);
      saveResponseToCache(
          OperationType.CAPTURE,
          authorisationId,
          captureRequest.idempotencyKey(),
          new CachedCaptureResponse(expectedFingerprint, resolvedResponse));
      return resolvedResponse;
    }
  }

  private String buildCaptureRequestFingerprint(UUID authorisationId) {
    String canonicalRequest = RequestHashing.canonicalJoin(authorisationId.toString());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
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
    log.debug(
        "Handling reverse request, authorisationId={}, idempotencyKey={}, reasonCode={}",
        authorisationId,
        reverseRequest.idempotencyKey(),
        reverseRequest.reasonCode());

    String expectedFingerprint =
        buildReverseRequestFingerprint(authorisationId, reverseRequest.reasonCode());
    Optional<ReverseResponse> cachedResponseOpt =
        getCachedResponse(
            OperationType.REVERSE,
            authorisationId,
            reverseRequest.idempotencyKey(),
            CachedReverseResponse.class,
            expectedFingerprint);
    if (cachedResponseOpt.isPresent()) {
      log.debug(
          "Found reverseResponse in cache for operation=reverse, authorisationId={}, idempotencyKey={}",
          authorisationId,
          reverseRequest.idempotencyKey());
      return cachedResponseOpt.get();
    }

    UUID correlationId = correlationIdResolver.resolveOrCreate();
    try {
      ReverseResponse reverseResponse =
          authorisationTransactionalExecutor.reverseInTransaction(
              authorisationId, reverseRequest, correlationId);
      saveResponseToCache(
          OperationType.REVERSE,
          authorisationId,
          reverseRequest.idempotencyKey(),
          new CachedReverseResponse(expectedFingerprint, reverseResponse));
      return reverseResponse;
    } catch (ConcurrentIdempotencyRaceException e) {
      log.warn(
          "Resolving reverse idempotency after concurrent race, authorisationId={}, idempotencyKey={}",
          authorisationId,
          reverseRequest.idempotencyKey(),
          e);
      ReverseResponse resolvedResponse =
          resolveIdempotencyAfterReverseRollback(authorisationId, reverseRequest);
      saveResponseToCache(
          OperationType.REVERSE,
          authorisationId,
          reverseRequest.idempotencyKey(),
          new CachedReverseResponse(expectedFingerprint, resolvedResponse));
      return resolvedResponse;
    }
  }

  private String buildReverseRequestFingerprint(
      UUID authorisationId, AuthorisationEventReason reasonCode) {
    String canonicalRequest =
        RequestHashing.canonicalJoin(
            authorisationId.toString(), reasonCode == null ? null : reasonCode.name());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
  }

  private ReverseResponse resolveIdempotencyAfterReverseRollback(
      UUID authorisationId, ReverseRequest reverseRequest) {
    AuthorisationEntity authorisationEntity =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));

    return authorisationEventRepository
        .findByAccountIdAndEventTypeAndIdempotencyKey(
            authorisationEntity.getAccountId(),
            reverseRequest.idempotencyKey(),
            EventType.AUTHORISATION_REVERSED.toString())
        .map(
            event ->
                new ReverseResponse(
                    authorisationId,
                    reverseRequest.idempotencyKey(),
                    authorisationEntity.getAmount(),
                    authorisationEntity.getCurrencyCode(),
                    AuthorisationStatus.REVERSED,
                    event.getReasonCode() != null
                        ? event.getReasonCode()
                        : reverseRequest.reasonCode(),
                    authorisationEntity.getUpdatedAt()))
        .orElseThrow(IdempotencyConflictException::new);
  }
}
