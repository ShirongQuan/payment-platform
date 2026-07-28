package org.example.auth.authorisation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.example.auth.authorisation.api.AuthorisationResponse;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorisationServiceImplTest {

  @Mock private AuthorisationTransactionalExecutor transactionalExecutor;
  @Mock private AuthorisationRepository authorisationRepository;

  private AuthorisationServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AuthorisationServiceImpl(transactionalExecutor, authorisationRepository);
  }

  @Test
  void shouldGetAuthorisationByIdWhenExist() {
    UUID accountId = UUID.randomUUID();
    AuthorisationEntity existingEntity =
        existingAuthorisation(accountId, "key", BigDecimal.TEN, "GBP", "");
    when(authorisationRepository.findById(any())).thenReturn(Optional.of(existingEntity));

    AuthorisationResponse response = service.getAuthorisationById(existingEntity.getId());
    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.idempotencyKey()).isNull();
    assertThat(response.amount()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(response.currencyCode()).isEqualTo("GBP");
    assertThat(response.merchantReference()).isEqualTo("");
    assertThat(response.status()).isEqualTo(AuthorisationStatus.AUTHORISED);
    assertThat(response.createdAt()).isNotNull();
    assertThat(response.updatedAt()).isNotNull();
  }

  @Test
  void shouldFailWhenFindAuthorisationNotExist() {
    when(authorisationRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

    assertThatExceptionOfType(AuthorisationNotFoundException.class)
        .isThrownBy(() -> service.getAuthorisationById(UUID.randomUUID()));
  }

  @Test
  void authorise_returnsExistingAuthorisation_afterRollbackFromConcurrentInsertRace() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-1";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "usd", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        existingAuthorisation(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    AuthorisationResponse response = service.authorise(request);

    assertEquals(accountId, response.accountId());
    assertEquals(idempotencyKey, response.idempotencyKey());
    assertEquals(AuthorisationStatus.AUTHORISED, response.status());
    verify(authorisationRepository).findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerPayloadIsDifferent() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-1";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing = conflictingAuthorisation(new BigDecimal("99.00"));

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request));
  }

  @Test
  void authorise_shouldCallExecutorWithNormalizedCurrency() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-normalized";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "usd", "merchant-1");

    AuthorisationResponse executorResponse =
        new AuthorisationResponse(
            UUID.randomUUID(),
            accountId,
            idempotencyKey,
            new BigDecimal("10.00"),
            "USD",
            "merchant-1",
            AuthorisationStatus.AUTHORISED,
            OffsetDateTime.now(),
            OffsetDateTime.now());

    when(transactionalExecutor.authoriseInTransaction(request, "USD")).thenReturn(executorResponse);

    AuthorisationResponse response = service.authorise(request);

    assertThat(response.currencyCode()).isEqualTo("USD");
    verify(transactionalExecutor).authoriseInTransaction(request, "USD");
    verify(authorisationRepository, never()).findByAccountIdAndIdempotencyKey(any(), any());
  }

  @Test
  void authorise_shouldReturnExecutorResponse_whenNoRace() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-success";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("42.00"), "USD", "merchant-1");

    AuthorisationResponse executorResponse =
        new AuthorisationResponse(
            UUID.randomUUID(),
            accountId,
            idempotencyKey,
            new BigDecimal("42.00"),
            "USD",
            "merchant-1",
            AuthorisationStatus.AUTHORISED,
            OffsetDateTime.now(),
            OffsetDateTime.now());

    when(transactionalExecutor.authoriseInTransaction(request, "USD")).thenReturn(executorResponse);

    assertThat(service.authorise(request)).isSameAs(executorResponse);
    verify(authorisationRepository, never()).findByAccountIdAndIdempotencyKey(any(), any());
  }

  @Test
  void authorise_shouldThrowIllegalState_whenRaceButNoExistingRowFound() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-missing";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.authorise(request))
        .withMessageContaining("Duplicate idempotency key detected");
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerCurrencyDiffers() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-currency";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing = mock(AuthorisationEntity.class);
    when(existing.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(existing.getCurrencyCode()).thenReturn("EUR");
    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request));
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerMerchantReferenceDiffers() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-merchant";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        raceWinnerAuthorisation(new BigDecimal("10.00"), "USD", "merchant-other");
    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request));
  }

  @Test
  void authorise_acceptsRaceWinner_whenMerchantReferenceIsNullOnBothSides() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-null-merchant";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, new BigDecimal("10.00"), "USD", null);

    when(transactionalExecutor.authoriseInTransaction(request, "USD"))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        existingAuthorisation(accountId, idempotencyKey, new BigDecimal("10.00"), "USD", null);
    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    AuthorisationResponse response = service.authorise(request);

    assertThat(response.merchantReference()).isNull();
    assertThat(response.currencyCode()).isEqualTo("USD");
  }

  private static AuthorisationEntity existingAuthorisation(
      UUID accountId,
      String idempotencyKey,
      BigDecimal amount,
      String currency,
      String merchantReference) {
    OffsetDateTime now = OffsetDateTime.now();
    AuthorisationEntity entity = mock(AuthorisationEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getAccountId()).thenReturn(accountId);
    when(entity.getAmount()).thenReturn(amount);
    when(entity.getCurrencyCode()).thenReturn(currency);
    when(entity.getMerchantReference()).thenReturn(merchantReference);
    when(entity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(entity.getCreatedAt()).thenReturn(now);
    when(entity.getUpdatedAt()).thenReturn(now);
    return entity;
  }

  private static AuthorisationEntity conflictingAuthorisation(BigDecimal amount) {
    AuthorisationEntity entity = mock(AuthorisationEntity.class);
    when(entity.getAmount()).thenReturn(amount);
    return entity;
  }

  private static AuthorisationEntity raceWinnerAuthorisation(
      BigDecimal amount, String currencyCode, String merchantReference) {
    AuthorisationEntity entity = mock(AuthorisationEntity.class);
    when(entity.getAmount()).thenReturn(amount);
    when(entity.getCurrencyCode()).thenReturn(currencyCode);
    when(entity.getMerchantReference()).thenReturn(merchantReference);
    return entity;
  }
}
