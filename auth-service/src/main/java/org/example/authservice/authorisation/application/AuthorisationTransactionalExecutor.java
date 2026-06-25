package org.example.authservice.authorisation.application;

import java.util.Objects;
import java.util.Optional;
import org.example.authservice.account.domain.Account;
import org.example.authservice.account.infrastructure.AccountEntity;
import org.example.authservice.account.infrastructure.AccountMapper;
import org.example.authservice.account.infrastructure.AccountRepository;
import org.example.authservice.authorisation.AuthorisationStatus;
import org.example.authservice.authorisation.api.AuthorisationRequest;
import org.example.authservice.authorisation.api.AuthorisationResponse;
import org.example.authservice.authorisation.domain.Authorisation;
import org.example.authservice.authorisation.infrastructure.AuthorisationEntity;
import org.example.authservice.authorisation.infrastructure.AuthorisationMapper;
import org.example.authservice.authorisation.infrastructure.AuthorisationRepository;
import org.example.authservice.common.exception.AccountNotFoundException;
import org.example.authservice.common.exception.IdempotencyConflictException;
import org.example.authservice.common.exception.InsufficientFundException;
import org.example.authservice.outbox.OutboxEventService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorisationTransactionalExecutor {
  private final AccountRepository accountRepository;
  private final AuthorisationRepository authorisationRepository;
  private final OutboxEventService outboxEventService;

  public AuthorisationTransactionalExecutor(
      AccountRepository accountRepository,
      AuthorisationRepository authorisationRepository,
      OutboxEventService outboxEventService) {
    this.accountRepository = accountRepository;
    this.authorisationRepository = authorisationRepository;
    this.outboxEventService = outboxEventService;
  }

  @Transactional(rollbackFor = Exception.class)
  public AuthorisationResponse authoriseInTransaction(
      AuthorisationRequest request, String normalizedCurrency) {
    Optional<AuthorisationEntity> entityOpt =
        authorisationRepository.findByAccountIdAndIdempotencyKey(
            request.accountId(), request.idempotencyKey());

    if (entityOpt.isPresent()) {
      return validateAndBuildIdempotentResponse(entityOpt.get(), request, normalizedCurrency);
    }

    Authorisation authorisation;
    try {
      AccountEntity accountEntity =
          accountRepository
              .findById(request.accountId())
              .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
      Account account = AccountMapper.toAccount(accountEntity);
      account.reserve(request.amount(), normalizedCurrency);

      accountEntity.setAvailableBalance(account.getAvailableBalance());
      accountEntity.setReservedBalance(account.getReservedBalance());
      accountRepository.flush();

      authorisation =
          new Authorisation(
              request.accountId(),
              request.idempotencyKey(),
              request.amount(),
              normalizedCurrency,
              request.merchantReference(),
              AuthorisationStatus.AUTHORISED,
              "");
    } catch (InsufficientFundException ife) {
      authorisation =
          new Authorisation(
              request.accountId(),
              request.idempotencyKey(),
              request.amount(),
              normalizedCurrency,
              request.merchantReference(),
              AuthorisationStatus.DECLINED,
              "Insufficient funds");
    }

    try {
      authorisationRepository.saveAndFlush(AuthorisationMapper.toEntity(authorisation));
    } catch (DataIntegrityViolationException e) {
      // Fail fast so all side effects in this transaction are rolled back.
      throw new ConcurrentIdempotencyRaceException(e);
    }

    outboxEventService.enqueueAuthorisation(authorisation);

    return new AuthorisationResponse(
        authorisation.getId(),
        authorisation.getAccountId(),
        authorisation.getIdempotencyKey(),
        authorisation.getAmount(),
        authorisation.getCurrencyCode(),
        authorisation.getMerchantReference(),
        authorisation.getStatus(),
        authorisation.getFailureReason(),
        authorisation.getCreatedAt(),
        authorisation.getUpdatedAt());
  }

  private AuthorisationResponse validateAndBuildIdempotentResponse(
      AuthorisationEntity entity, AuthorisationRequest request, String normalizedCurrency) {
    if (isSameIdempotentRequest(entity, request, normalizedCurrency)) {
      return new AuthorisationResponse(
          entity.getId(),
          entity.getAccountId(),
          entity.getIdempotencyKey(),
          entity.getAmount(),
          entity.getCurrencyCode(),
          entity.getMerchantReference(),
          entity.getStatus(),
          entity.getFailureReason(),
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

