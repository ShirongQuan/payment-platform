package org.example.auth.authorisation.application;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.account.domain.Account;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountMapper;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationMapper;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.OperationType;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.AuthorisationIllegalStateException;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.common.exception.InsufficientFundException;
import org.example.auth.outbox.application.OutboxEventService;
import org.example.auth.outbox.domain.EventType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
/**
 * Transactional boundary for authorise/capture state transitions.
 *
 * <p>This component executes idempotency checks, account balance mutations, event persistence, and
 * outbox enqueueing in one transaction so downstream consumers observe consistent business events.
 */
public class AuthorisationTransactionalExecutorImpl implements AuthorisationTransactionalExecutor {
  private final AccountRepository accountRepository;
  private final AuthorisationRepository authorisationRepository;
  private final AuthorisationEventRepository authorisationEventRepository;
  private final OutboxEventService outboxEventService;
  private final AccountMapper accountMapper;
  private final AuthorisationMapper authorisationMapper;

  public AuthorisationTransactionalExecutorImpl(
      AccountRepository accountRepository,
      AuthorisationRepository authorisationRepository,
      AuthorisationEventRepository authorisationEventRepository,
      OutboxEventService outboxEventService,
      AccountMapper accountMapper,
      AuthorisationMapper authorisationMapper) {
    this.accountRepository = accountRepository;
    this.authorisationRepository = authorisationRepository;
    this.authorisationEventRepository = authorisationEventRepository;
    this.outboxEventService = outboxEventService;
    this.accountMapper = accountMapper;
    this.authorisationMapper = authorisationMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency) {
    log.debug(
        "Starting authorise transaction, accountId={}, idempotencyKey={}, amount={}, currency={}",
        request.accountId(),
        request.idempotencyKey(),
        request.amount(),
        normalizedCurrency);
    Optional<AuthorisationEntity> entityOpt =
        authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            request.accountId(), request.idempotencyKey());

    if (entityOpt.isPresent()) {
      log.debug("authorisation with the same idempotency key {} exist", request.idempotencyKey());
      return validateAndBuildIdempotentResponse(entityOpt.get(), request, normalizedCurrency);
    }

    Authorisation authorisation;
    AuthorisationEventEntity authorisationEventEntity;
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    try {
      AccountEntity accountEntity =
          accountRepository
              .findById(request.accountId())
              .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
      Account account = accountMapper.toAccount(accountEntity);
      log.debug(
          "Loaded account for authorise, accountId={}, availableBalance={}, reservedBalance={}",
          accountEntity.getId(),
          accountEntity.getAvailableBalance(),
          accountEntity.getReservedBalance());
      account.reserve(request.amount(), normalizedCurrency);

      accountEntity.setAvailableBalance(account.getAvailableBalance());
      accountEntity.setReservedBalance(account.getReservedBalance());
      log.debug(
          "Reserved amount for authorise, accountId={}, availableBalance={}, reservedBalance={}",
          accountEntity.getId(),
          accountEntity.getAvailableBalance(),
          accountEntity.getReservedBalance());
      // send sql to trigger DB constraint validation earlier
      accountRepository.flush();

      authorisation =
          new Authorisation(
              request.accountId(),
              request.amount(),
              normalizedCurrency,
              request.merchantReference(),
              AuthorisationStatus.AUTHORISED);

      authorisationEventEntity =
          new AuthorisationEventEntity(
              eventId,
              authorisation.getId(),
              request.accountId(),
              EventType.AUTHORISATION_AUTHORISED,
              request.idempotencyKey(),
              request.amount(),
              normalizedCurrency,
              AuthorisationEventReason.NONE,
              correlationId,
              OffsetDateTime.now());

    } catch (InsufficientFundException ife) {
      log.debug(
          "Authorise declined due to insufficient funds, accountId={}, requestedAmount={}",
          request.accountId(),
          request.amount());
      // in this case, Account in DB will remain intact and the Authorisation will persist
      authorisation =
          new Authorisation(
              request.accountId(),
              request.amount(),
              normalizedCurrency,
              request.merchantReference(),
              AuthorisationStatus.DECLINED);

      authorisationEventEntity =
          new AuthorisationEventEntity(
              eventId,
              authorisation.getId(),
              request.accountId(),
              EventType.AUTHORISATION_DECLINED,
              request.idempotencyKey(),
              request.amount(),
              normalizedCurrency,
              AuthorisationEventReason.INSUFFICIENT_FUNDS,
              correlationId,
              OffsetDateTime.now());
    }
    AuthorisationEntity authorisationEntity = authorisationMapper.toEntity(authorisation);
    try {
      authorisationRepository.saveAndFlush(authorisationEntity);
      authorisationEventRepository.saveAndFlush(authorisationEventEntity);
      log.debug(
          "Persisted authorise state and event, authorisationId={}, eventId={}, eventType={}",
          authorisationEntity.getId(),
          authorisationEventEntity.getEventId(),
          authorisationEventEntity.getEventType());
    } catch (DataIntegrityViolationException e) {
      // race-condition safety net for idempotency under concurrency.
      // DB constraint: uq_authorisation_event_account_eventtype_idempotency
      // Fail-fast signal of concurrent idempotency race, so the whole transaction rolls back
      // consistently.
      log.warn(
          "Concurrent idempotency race while persisting authorise, accountId={}, idempotencyKey={}",
          request.accountId(),
          request.idempotencyKey(),
          e);
      throw new ConcurrentIdempotencyRaceException(e);
    }

    log.debug(
        "Enqueueing authorise outbox event, authorisationId={}, eventId={}",
        authorisationEntity.getId(),
        authorisationEventEntity.getEventId());
    outboxEventService.enqueueAuthorisation(
        authorisationEntity, authorisationEventEntity, OperationType.AUTHORISE);

    return new AuthorisationResponse(
        authorisation.getId(),
        authorisation.getAccountId(),
        request.idempotencyKey(),
        authorisation.getAmount(),
        authorisation.getCurrencyCode(),
        authorisation.getMerchantReference(),
        authorisation.getStatus(),
        authorisation.getCreatedAt(),
        authorisation.getUpdatedAt());
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
  public CaptureResponse captureInTransaction(UUID authorisationId, CaptureRequest captureRequest) {
    log.debug(
        "Starting capture transaction, authorisationId={}, idempotencyKey={}",
        authorisationId,
        captureRequest.idempotencyKey());

    // load authorisation by id, fail if not found
    AuthorisationEntity authorisationEntity =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));

    AuthorisationStatus status = authorisationEntity.getStatus();
    if (status.equals(AuthorisationStatus.CAPTURED)) {
      log.debug(
          "Authorisation already captured, checking capture idempotency replay, authorisationId={}, idempotencyKey={}",
          authorisationId,
          captureRequest.idempotencyKey());
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
          .orElseThrow(
              () -> {
                log.warn(
                    "Authorisation {} cannot be captured because it is already CAPTURED.",
                    authorisationId);
                return new AuthorisationIllegalStateException(
                    authorisationId, AuthorisationStatus.CAPTURED, "capture");
              });
    } else if (!status.equals(AuthorisationStatus.AUTHORISED)) {
      // if status != AUTHORISED → fail
      log.warn(
          "Capture rejected due to illegal state, authorisationId={}, currentStatus={}",
          authorisationId,
          status);
      throw new AuthorisationIllegalStateException(authorisationId, status, "capture");
    }

    // TODO: correlationId
    UUID correlationId = UUID.randomUUID();
    // load account
    // move reserved balance out
    AccountEntity accountEntity =
        accountRepository
            .findById(authorisationEntity.getAccountId())
            .orElseThrow(() -> new AccountNotFoundException(authorisationEntity.getAccountId()));
    Account account = accountMapper.toAccount(accountEntity);
    log.debug(
        "Loaded account for capture, accountId={}, reservedBalance={}",
        accountEntity.getId(),
        accountEntity.getReservedBalance());
    account.capture(authorisationEntity.getAmount(), authorisationEntity.getCurrencyCode());
    accountEntity.setReservedBalance(account.getReservedBalance());
    log.debug(
        "Captured reserved amount, accountId={}, newReservedBalance={}",
        accountEntity.getId(),
        accountEntity.getReservedBalance());

    // update authorization to CAPTURED
    authorisationEntity.setStatus(AuthorisationStatus.CAPTURED);
    // write authorization event
    AuthorisationEventEntity authorisationEventEntity =
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationEntity.getId(),
            authorisationEntity.getAccountId(),
            EventType.AUTHORISATION_CAPTURED,
            captureRequest.idempotencyKey(),
            authorisationEntity.getAmount(),
            authorisationEntity.getCurrencyCode(),
            AuthorisationEventReason.NONE,
            correlationId,
            OffsetDateTime.now());

    try {
      authorisationRepository.saveAndFlush(authorisationEntity);
      authorisationEventRepository.saveAndFlush(authorisationEventEntity);
      log.debug(
          "Persisted capture state and event, authorisationId={}, eventId={}",
          authorisationEntity.getId(),
          authorisationEventEntity.getEventId());
    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Concurrent idempotency race while persisting capture, authorisationId={}, idempotencyKey={}",
          authorisationId,
          captureRequest.idempotencyKey(),
          e);
      throw new ConcurrentIdempotencyRaceException(e);
    }
    // write outbox event
    log.debug(
        "Enqueueing capture outbox event, authorisationId={}, eventId={}",
        authorisationEntity.getId(),
        authorisationEventEntity.getEventId());
    outboxEventService.enqueueAuthorisation(
        authorisationEntity, authorisationEventEntity, OperationType.CAPTURE);

    // return response
    return new CaptureResponse(
        authorisationId,
        captureRequest.idempotencyKey(),
        authorisationEntity.getAmount(),
        authorisationEntity.getCurrencyCode(),
        AuthorisationStatus.CAPTURED,
        OffsetDateTime.now());
  }

  @Override
  public ReverseResponse reverseInTransaction(UUID authorisationId, ReverseRequest reverseRequest) {
    log.debug(
        "Starting reverse transaction, authorisationId={}, idempotencyKey={}, reasonCode={}",
        authorisationId,
        reverseRequest.idempotencyKey(),
        reverseRequest.reasonCode());

    AuthorisationEntity authorisationEntity =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));

    AuthorisationStatus status = authorisationEntity.getStatus();
    if (status.equals(AuthorisationStatus.REVERSED)) {
      log.debug(
          "Authorisation already reversed, checking reverse idempotency replay, authorisationId={}, idempotencyKey={}",
          authorisationId,
          reverseRequest.idempotencyKey());
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
          .orElseThrow(
              () -> {
                log.warn(
                    "Authorisation {} cannot be reversed because it is already REVERSED.",
                    authorisationId);
                return new AuthorisationIllegalStateException(
                    authorisationId, AuthorisationStatus.REVERSED, "reverse");
              });
    } else if (!status.equals(AuthorisationStatus.AUTHORISED)) {
      log.warn(
          "Reverse rejected due to illegal state, authorisationId={}, currentStatus={}",
          authorisationId,
          status);
      throw new AuthorisationIllegalStateException(authorisationId, status, "reverse");
    }

    UUID correlationId = UUID.randomUUID();
    AccountEntity accountEntity =
        accountRepository
            .findById(authorisationEntity.getAccountId())
            .orElseThrow(() -> new AccountNotFoundException(authorisationEntity.getAccountId()));
    Account account = accountMapper.toAccount(accountEntity);
    log.debug(
        "Loaded account for reverse, accountId={}, availableBalance={}, reservedBalance={}",
        accountEntity.getId(),
        accountEntity.getAvailableBalance(),
        accountEntity.getReservedBalance());
    account.reverse(authorisationEntity.getAmount(), authorisationEntity.getCurrencyCode());
    accountEntity.setAvailableBalance(account.getAvailableBalance());
    accountEntity.setReservedBalance(account.getReservedBalance());
    log.debug(
        "Reversed reserved amount, accountId={}, newAvailableBalance={}, newReservedBalance={}",
        accountEntity.getId(),
        accountEntity.getAvailableBalance(),
        accountEntity.getReservedBalance());

    authorisationEntity.setStatus(AuthorisationStatus.REVERSED);
    AuthorisationEventEntity authorisationEventEntity =
        new AuthorisationEventEntity(
            UUID.randomUUID(),
            authorisationEntity.getId(),
            authorisationEntity.getAccountId(),
            EventType.AUTHORISATION_REVERSED,
            reverseRequest.idempotencyKey(),
            authorisationEntity.getAmount(),
            authorisationEntity.getCurrencyCode(),
            reverseRequest.reasonCode(),
            correlationId,
            OffsetDateTime.now());

    try {
      authorisationRepository.saveAndFlush(authorisationEntity);
      authorisationEventRepository.saveAndFlush(authorisationEventEntity);
      log.debug(
          "Persisted reverse state and event, authorisationId={}, eventId={}",
          authorisationEntity.getId(),
          authorisationEventEntity.getEventId());
    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Concurrent idempotency race while persisting reverse, authorisationId={}, idempotencyKey={}",
          authorisationId,
          reverseRequest.idempotencyKey(),
          e);
      throw new ConcurrentIdempotencyRaceException(e);
    }

    log.debug(
        "Enqueueing reverse outbox event, authorisationId={}, eventId={}",
        authorisationEntity.getId(),
        authorisationEventEntity.getEventId());
    outboxEventService.enqueueAuthorisation(
        authorisationEntity, authorisationEventEntity, OperationType.REVERSE);

    return new ReverseResponse(
        authorisationId,
        reverseRequest.idempotencyKey(),
        authorisationEntity.getAmount(),
        authorisationEntity.getCurrencyCode(),
        AuthorisationStatus.REVERSED,
        reverseRequest.reasonCode(),
        OffsetDateTime.now());
  }
}
