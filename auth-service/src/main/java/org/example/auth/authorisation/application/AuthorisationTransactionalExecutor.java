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
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationMapper;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.ErrorCode;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.common.exception.InsufficientFundException;
import org.example.auth.outbox.application.OutboxEventService;
import org.example.auth.outbox.domain.EventType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthorisationTransactionalExecutor {
  private final AccountRepository accountRepository;
  private final AuthorisationRepository authorisationRepository;
  private final AuthorisationEventRepository authorisationEventRepository;
  private final OutboxEventService outboxEventService;
  private final AccountMapper accountMapper;
  private final AuthorisationMapper authorisationMapper;

  public AuthorisationTransactionalExecutor(
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

  @Transactional(rollbackFor = Exception.class)
  public AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency) {
    Optional<AuthorisationEntity> entityOpt =
        authorisationRepository.findByAccountIdAndIdempotencyKey(
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
      account.reserve(request.amount(), normalizedCurrency);

      accountEntity.setAvailableBalance(account.getAvailableBalance());
      accountEntity.setReservedBalance(account.getReservedBalance());
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
              "",
              correlationId,
              OffsetDateTime.now());

    } catch (InsufficientFundException ife) {
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
              ErrorCode.INSUFFICIENT_FUNDS.toString(),
              correlationId,
              OffsetDateTime.now());
    }

    try {
      authorisationRepository.saveAndFlush(authorisationMapper.toEntity(authorisation));
      authorisationEventRepository.saveAndFlush(authorisationEventEntity);
    } catch (DataIntegrityViolationException e) {
      // Fail-fast signal of concurrent idempotency race, so the whole transaction rolls back
      // consistently.
      throw new ConcurrentIdempotencyRaceException(e);
    }

    outboxEventService.enqueueAuthorisation(authorisation, authorisationEventEntity);

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
}
