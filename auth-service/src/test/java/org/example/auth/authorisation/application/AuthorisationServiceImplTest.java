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
import org.example.auth.authorisation.api.CaptureRequest;
import org.example.auth.authorisation.api.CaptureResponse;
import org.example.auth.authorisation.api.ReverseRequest;
import org.example.auth.authorisation.api.ReverseResponse;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.outbox.domain.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorisationServiceImplTest {

  @Mock private AuthorisationTransactionalExecutorImpl transactionalExecutor;
  @Mock private AuthorisationRepository authorisationRepository;
  @Mock private AuthorisationEventRepository authorisationEventRepository;

  private AuthorisationServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new AuthorisationServiceImpl(
            transactionalExecutor, authorisationRepository, authorisationEventRepository);
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

  @Test
  void capture_shouldReturnExecutorResponse_whenNoRace() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-key");

    CaptureResponse response =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(transactionalExecutor.captureInTransaction(authorisationId, request)).thenReturn(response);

    assertThat(service.capture(authorisationId, request)).isSameAs(response);
    verify(authorisationRepository, never()).findById(any(UUID.class));
    verify(authorisationEventRepository, never())
        .findByAccountIdAndEventTypeAndIdempotencyKey(
            any(UUID.class), any(String.class), any(String.class));
  }

  @Test
  void capture_returnsExistingCapture_afterRollbackFromConcurrentInsertRace() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-key");

    when(transactionalExecutor.captureInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(2);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getUpdatedAt()).thenReturn(updatedAt);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AuthorisationEventEntity capturedEvent = mock(AuthorisationEventEntity.class);
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, request.idempotencyKey(), EventType.AUTHORISATION_CAPTURED.toString()))
        .thenReturn(Optional.of(capturedEvent));

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.idempotencyKey()).isEqualTo(request.idempotencyKey());
    assertThat(response.status()).isEqualTo(AuthorisationStatus.CAPTURED);
    assertThat(response.capturedAmount()).isEqualByComparingTo("10.00");
  }

  @Test
  void capture_throwsConflict_whenRaceWinnerCaptureEventMissing() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-key");

    when(transactionalExecutor.captureInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, request.idempotencyKey(), EventType.AUTHORISATION_CAPTURED.toString()))
        .thenReturn(Optional.empty());

    assertThrows(
        IdempotencyConflictException.class, () -> service.capture(authorisationId, request));
  }

  @Test
  void capture_throwsNotFound_whenRaceAndAuthorisationMissing() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-key");

    when(transactionalExecutor.captureInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class, () -> service.capture(authorisationId, request));
  }

  @Test
  void reverse_shouldReturnExecutorResponse_whenNoRace() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse response =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(transactionalExecutor.reverseInTransaction(authorisationId, request)).thenReturn(response);

    assertThat(service.reverse(authorisationId, request)).isSameAs(response);
    verify(authorisationRepository, never()).findById(any(UUID.class));
    verify(authorisationEventRepository, never())
        .findByAccountIdAndEventTypeAndIdempotencyKey(
            any(UUID.class), any(String.class), any(String.class));
  }

  @Test
  void reverse_returnsExistingReverse_afterRollbackFromConcurrentInsertRace() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(transactionalExecutor.reverseInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(2);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getUpdatedAt()).thenReturn(updatedAt);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AuthorisationEventEntity reversedEvent = mock(AuthorisationEventEntity.class);
    when(reversedEvent.getReasonCode()).thenReturn(AuthorisationEventReason.CUSTOMER_REQUEST);
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, request.idempotencyKey(), EventType.AUTHORISATION_REVERSED.toString()))
        .thenReturn(Optional.of(reversedEvent));

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.idempotencyKey()).isEqualTo(request.idempotencyKey());
    assertThat(response.status()).isEqualTo(AuthorisationStatus.REVERSED);
    assertThat(response.reversedAmount()).isEqualByComparingTo("10.00");
    assertThat(response.reasonCode()).isEqualTo(AuthorisationEventReason.CUSTOMER_REQUEST);
  }

  @Test
  void reverse_throwsConflict_whenRaceWinnerReverseEventMissing() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(transactionalExecutor.reverseInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId, request.idempotencyKey(), EventType.AUTHORISATION_REVERSED.toString()))
        .thenReturn(Optional.empty());

    assertThrows(
        IdempotencyConflictException.class, () -> service.reverse(authorisationId, request));
  }

  @Test
  void reverse_throwsNotFound_whenRaceAndAuthorisationMissing() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(transactionalExecutor.reverseInTransaction(authorisationId, request))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class, () -> service.reverse(authorisationId, request));
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
