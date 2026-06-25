package org.example.authservice.authorisation.application;

import java.util.Objects;
import java.util.UUID;
import org.example.authservice.authorisation.api.AuthorisationRequest;
import org.example.authservice.authorisation.api.AuthorisationResponse;
import org.example.authservice.authorisation.infrastructure.AuthorisationEntity;
import org.example.authservice.authorisation.infrastructure.AuthorisationRepository;
import org.example.authservice.common.exception.AuthorisationNotFoundException;
import org.example.authservice.common.exception.IdempotencyConflictException;
import org.example.authservice.common.validation.ValidationHelpers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorisationServiceImpl implements AuthorisationService {
  private final AuthorisationTransactionalExecutor authorisationTransactionalExecutor;
  private final AuthorisationRepository authorisationRepository;

  public AuthorisationServiceImpl(
      AuthorisationTransactionalExecutor authorisationTransactionalExecutor,
      AuthorisationRepository authorisationRepository) {
    this.authorisationTransactionalExecutor = authorisationTransactionalExecutor;
    this.authorisationRepository = authorisationRepository;
  }

  @Override
  public AuthorisationResponse authorise(AuthorisationRequest request) {
    String normalizedCurrency =
        ValidationHelpers.normalizeAndValidateCurrency(request.currencyCode());
    try {
      return authorisationTransactionalExecutor.authoriseInTransaction(request, normalizedCurrency);
    } catch (ConcurrentIdempotencyRaceException e) {
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
      return toResponse(entity);
    }
    throw new IdempotencyConflictException();
  }

  private boolean isSameIdempotentRequest(
      AuthorisationEntity entity, AuthorisationRequest request, String normalizedCurrency) {
    return entity.getAmount().compareTo(request.amount()) == 0
        && entity.getCurrencyCode().equals(normalizedCurrency)
        && Objects.equals(entity.getMerchantReference(), request.merchantReference());
  }

  private AuthorisationResponse toResponse(AuthorisationEntity entity) {
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

  @Override
  @Transactional(readOnly = true)
  public AuthorisationResponse getAuthorisationById(UUID authorisationId) {
    AuthorisationEntity authorisation =
        authorisationRepository
            .findById(authorisationId)
            .orElseThrow(() -> new AuthorisationNotFoundException(authorisationId));
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
}
