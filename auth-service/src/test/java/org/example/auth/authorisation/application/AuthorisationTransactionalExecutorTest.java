package org.example.auth.authorisation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountMapper;
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
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationMapper;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.OperationType;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.AuthorisationIllegalStateException;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.fraud.FraudDecision;
import org.example.auth.outbox.application.OutboxEventService;
import org.example.auth.outbox.domain.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AuthorisationTransactionalExecutorTest {

  @Mock private AccountRepository accountRepository;

  @Mock private AuthorisationRepository authorisationRepository;

  @Mock private AuthorisationEventRepository authorisationEventRepository;

  @Mock private OutboxEventService outboxEventService;
  @Spy private AccountMapper accountMapper = Mappers.getMapper(AccountMapper.class);

  @Spy
  private AuthorisationMapper authorisationMapper = Mappers.getMapper(AuthorisationMapper.class);

  @InjectMocks private AuthorisationTransactionalExecutorImpl executor;

  @Test
  void shouldReturnExistingAuthRecordIfSameIdempotentRequest() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    AuthorisationEntity existing = existingAuthorisationEntity(accountId, idempotencyKey);

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    AuthorisationResponse response = authorise(request, "USD");

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.idempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(response.amount()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(response.currencyCode()).isEqualTo("USD");
    assertThat(response.status()).isEqualTo(AuthorisationStatus.AUTHORISED);

    verify(accountRepository, never()).findById(any(UUID.class));
    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldReturnIdempotencyConflictExceptionIfMismatchedIdempotentRequest() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    AuthorisationEntity existing = mock(AuthorisationEntity.class);
    when(existing.getAmount()).thenReturn(BigDecimal.ONE);

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    assertThatExceptionOfType(IdempotencyConflictException.class)
        .isThrownBy(() -> authorise(request, "USD"));

    verify(accountRepository, never()).findById(any(UUID.class));
    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldThrowExceptionIfAccountNotFound() {
    UUID accountId = UUID.randomUUID();
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, "key", BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, "key"))
        .thenReturn(Optional.empty());
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    AccountNotFoundException exception =
        assertThrows(AccountNotFoundException.class, () -> authorise(request, "USD"));
    assertThat(exception.getAccountId()).isEqualTo(accountId);

    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldPersistDeclinedAuthIfInsufficientFunds() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty());

    AccountEntity accountEntity = this.mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("5.00"));
    accountEntity.setReservedBalance(BigDecimal.ZERO);
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    AuthorisationResponse response = authorise(request, "USD");

    assertThat(response.status()).isEqualTo(AuthorisationStatus.DECLINED);

    ArgumentCaptor<AuthorisationEntity> entityCaptor =
        ArgumentCaptor.forClass(AuthorisationEntity.class);
    verify(authorisationRepository).saveAndFlush(entityCaptor.capture());
    assertThat(entityCaptor.getValue().getStatus()).isEqualTo(AuthorisationStatus.DECLINED);

    ArgumentCaptor<AuthorisationEventEntity> eventCaptor =
        ArgumentCaptor.forClass(AuthorisationEventEntity.class);
    verify(authorisationEventRepository).saveAndFlush(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getCurrencyCode()).isEqualTo("USD");
    assertThat(eventCaptor.getValue().getCorrelationId()).isNotNull();
    assertThat(eventCaptor.getValue().getCorrelationId()).isInstanceOf(UUID.class);

    assertThat(accountEntity.getAvailableBalance()).isEqualByComparingTo("5.00");
    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("0.00");
    verify(accountRepository, never()).flush();
    verify(outboxEventService, times(1))
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldCatchDataIntegrityViolationExceptionAndRethrow() {
    UUID accountId = UUID.randomUUID();
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, "key", BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, "key"))
        .thenReturn(Optional.empty());

    AccountEntity accountEntity = this.mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("1000.00"));
    accountEntity.setReservedBalance(BigDecimal.ZERO);
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    DataIntegrityViolationException cause = new DataIntegrityViolationException("duplicate key");
    when(authorisationRepository.saveAndFlush(any(AuthorisationEntity.class))).thenThrow(cause);

    ConcurrentIdempotencyRaceException ex =
        assertThrows(ConcurrentIdempotencyRaceException.class, () -> authorise(request, "USD"));
    assertThat(ex.getCause()).isEqualTo(cause);

    assertThat(accountEntity.getAvailableBalance()).isEqualByComparingTo("990.00");
    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("10.00");
    verify(accountRepository).flush();
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldAuthoriseInTransaction() {
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            any(UUID.class), any(String.class)))
        .thenReturn(Optional.empty());

    UUID accountId = UUID.randomUUID();
    AccountEntity accountEntity = this.mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("GBP");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(BigDecimal.valueOf(1000.0));
    accountEntity.setReservedBalance(BigDecimal.ZERO);
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(eq(accountId))).thenReturn(Optional.of(accountEntity));

    AuthorisationRequest request =
        new AuthorisationRequest(accountId, "key", BigDecimal.TEN, "gbp", "reference");
    AuthorisationResponse authResponse = authorise(request, "gbp");

    assertThat(authResponse).isNotNull();
    assertThat(authResponse.accountId()).isEqualTo(accountId);
    assertThat(authResponse.status()).isEqualTo(AuthorisationStatus.AUTHORISED);

    assertThat(accountEntity.getAvailableBalance()).isEqualByComparingTo("990.00");
    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("10.00");
    verify(accountRepository, times(1)).flush();

    verify(authorisationRepository, times(1)).saveAndFlush(any(AuthorisationEntity.class));
    ArgumentCaptor<AuthorisationEventEntity> eventCaptor =
        ArgumentCaptor.forClass(AuthorisationEventEntity.class);
    verify(authorisationEventRepository).saveAndFlush(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getCurrencyCode()).isEqualTo("gbp");
    assertThat(eventCaptor.getValue().getCorrelationId()).isNotNull();
    assertThat(eventCaptor.getValue().getCorrelationId()).isInstanceOf(UUID.class);
    verify(outboxEventService, times(1))
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldCaptureInTransactionWhenAuthorisationIsAuthorised() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest captureRequest = new CaptureRequest("capture-key");

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getId()).thenReturn(authorisationId);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AccountEntity accountEntity = mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("100.00"));
    accountEntity.setReservedBalance(new BigDecimal("10.00"));
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    CaptureResponse response =
        executor.captureInTransaction(authorisationId, captureRequest, UUID.randomUUID());

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.idempotencyKey()).isEqualTo(captureRequest.idempotencyKey());
    assertThat(response.capturedAmount()).isEqualByComparingTo("10.00");
    assertThat(response.status()).isEqualTo(AuthorisationStatus.CAPTURED);

    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("0.00");
    verify(authorisationEntity).setStatus(AuthorisationStatus.CAPTURED);
    verify(authorisationRepository).saveAndFlush(authorisationEntity);

    ArgumentCaptor<AuthorisationEventEntity> eventCaptor =
        ArgumentCaptor.forClass(AuthorisationEventEntity.class);
    verify(authorisationEventRepository).saveAndFlush(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventType.AUTHORISATION_CAPTURED);
    assertThat(eventCaptor.getValue().getIdempotencyKey())
        .isEqualTo(captureRequest.idempotencyKey());
    assertThat(eventCaptor.getValue().getCorrelationId()).isNotNull();

    verify(outboxEventService)
        .enqueueAuthorisation(authorisationEntity, eventCaptor.getValue(), OperationType.CAPTURE);
  }

  @Test
  void shouldReturnCapturedResponseWhenAlreadyCapturedWithMatchingIdempotency() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest captureRequest = new CaptureRequest("capture-key");

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(5);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.CAPTURED);
    when(authorisationEntity.getUpdatedAt()).thenReturn(updatedAt);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId,
            captureRequest.idempotencyKey(),
            EventType.AUTHORISATION_CAPTURED.toString()))
        .thenReturn(Optional.of(mock(AuthorisationEventEntity.class)));

    CaptureResponse response =
        executor.captureInTransaction(authorisationId, captureRequest, UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AuthorisationStatus.CAPTURED);
    assertThat(response.idempotencyKey()).isEqualTo(captureRequest.idempotencyKey());
    verify(accountRepository, never()).findById(any(UUID.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldThrowConflictWhenAlreadyCapturedWithDifferentIdempotency() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest captureRequest = new CaptureRequest("capture-key");

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.CAPTURED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId,
            captureRequest.idempotencyKey(),
            EventType.AUTHORISATION_CAPTURED.toString()))
        .thenReturn(Optional.empty());

    assertThrows(
        AuthorisationIllegalStateException.class,
        () -> executor.captureInTransaction(authorisationId, captureRequest, UUID.randomUUID()));
  }

  @Test
  void shouldThrowIllegalStateWhenCaptureOnNonAuthorisedStatus() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest captureRequest = new CaptureRequest("capture-key");

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.DECLINED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    assertThrows(
        AuthorisationIllegalStateException.class,
        () -> executor.captureInTransaction(authorisationId, captureRequest, UUID.randomUUID()));
  }

  @Test
  void shouldThrowNotFoundWhenCaptureAuthorisationMissing() {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class,
        () ->
            executor.captureInTransaction(
                authorisationId, new CaptureRequest("capture-key"), UUID.randomUUID()));
  }

  @Test
  void shouldWrapCaptureSaveRaceAsConcurrentIdempotencyRace() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest captureRequest = new CaptureRequest("capture-key");

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getId()).thenReturn(authorisationId);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AccountEntity accountEntity = mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("100.00"));
    accountEntity.setReservedBalance(new BigDecimal("10.00"));
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    DataIntegrityViolationException cause = new DataIntegrityViolationException("duplicate key");
    when(authorisationRepository.saveAndFlush(authorisationEntity)).thenThrow(cause);

    ConcurrentIdempotencyRaceException exception =
        assertThrows(
            ConcurrentIdempotencyRaceException.class,
            () ->
                executor.captureInTransaction(authorisationId, captureRequest, UUID.randomUUID()));

    assertThat(exception.getCause()).isEqualTo(cause);
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldReverseInTransactionWhenAuthorisationIsAuthorised() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest reverseRequest =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getId()).thenReturn(authorisationId);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AccountEntity accountEntity = mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("90.00"));
    accountEntity.setReservedBalance(new BigDecimal("10.00"));
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    ReverseResponse response =
        executor.reverseInTransaction(authorisationId, reverseRequest, UUID.randomUUID());

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.idempotencyKey()).isEqualTo(reverseRequest.idempotencyKey());
    assertThat(response.reversedAmount()).isEqualByComparingTo("10.00");
    assertThat(response.status()).isEqualTo(AuthorisationStatus.REVERSED);
    assertThat(response.reasonCode()).isEqualTo(AuthorisationEventReason.CUSTOMER_REQUEST);

    assertThat(accountEntity.getAvailableBalance()).isEqualByComparingTo("100.00");
    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("0.00");
    verify(authorisationEntity).setStatus(AuthorisationStatus.REVERSED);
    verify(authorisationRepository).saveAndFlush(authorisationEntity);

    ArgumentCaptor<AuthorisationEventEntity> eventCaptor =
        ArgumentCaptor.forClass(AuthorisationEventEntity.class);
    verify(authorisationEventRepository).saveAndFlush(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getEventType()).isEqualTo(EventType.AUTHORISATION_REVERSED);
    assertThat(eventCaptor.getValue().getIdempotencyKey())
        .isEqualTo(reverseRequest.idempotencyKey());
    assertThat(eventCaptor.getValue().getReasonCode())
        .isEqualTo(AuthorisationEventReason.CUSTOMER_REQUEST);
    assertThat(eventCaptor.getValue().getCorrelationId()).isNotNull();

    verify(outboxEventService)
        .enqueueAuthorisation(authorisationEntity, eventCaptor.getValue(), OperationType.REVERSE);
  }

  @Test
  void shouldReturnReversedResponseWhenAlreadyReversedWithMatchingIdempotency() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest reverseRequest =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusSeconds(5);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.REVERSED);
    when(authorisationEntity.getUpdatedAt()).thenReturn(updatedAt);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AuthorisationEventEntity reverseEvent = mock(AuthorisationEventEntity.class);
    when(reverseEvent.getReasonCode()).thenReturn(AuthorisationEventReason.CUSTOMER_REQUEST);
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId,
            reverseRequest.idempotencyKey(),
            EventType.AUTHORISATION_REVERSED.toString()))
        .thenReturn(Optional.of(reverseEvent));

    ReverseResponse response =
        executor.reverseInTransaction(authorisationId, reverseRequest, UUID.randomUUID());

    assertThat(response.status()).isEqualTo(AuthorisationStatus.REVERSED);
    assertThat(response.idempotencyKey()).isEqualTo(reverseRequest.idempotencyKey());
    assertThat(response.reasonCode()).isEqualTo(AuthorisationEventReason.CUSTOMER_REQUEST);
    verify(accountRepository, never()).findById(any(UUID.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  @Test
  void shouldThrowConflictWhenAlreadyReversedWithDifferentIdempotency() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest reverseRequest =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.REVERSED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));
    when(authorisationEventRepository.findByAccountIdAndEventTypeAndIdempotencyKey(
            accountId,
            reverseRequest.idempotencyKey(),
            EventType.AUTHORISATION_REVERSED.toString()))
        .thenReturn(Optional.empty());

    assertThrows(
        AuthorisationIllegalStateException.class,
        () -> executor.reverseInTransaction(authorisationId, reverseRequest, UUID.randomUUID()));
  }

  @Test
  void shouldThrowIllegalStateWhenReverseOnNonAuthorisedStatus() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest reverseRequest =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.CAPTURED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    assertThrows(
        AuthorisationIllegalStateException.class,
        () -> executor.reverseInTransaction(authorisationId, reverseRequest, UUID.randomUUID()));
  }

  @Test
  void shouldThrowNotFoundWhenReverseAuthorisationMissing() {
    UUID authorisationId = UUID.randomUUID();
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class,
        () ->
            executor.reverseInTransaction(
                authorisationId,
                new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST),
                UUID.randomUUID()));
  }

  @Test
  void shouldWrapReverseSaveRaceAsConcurrentIdempotencyRace() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest reverseRequest =
        new ReverseRequest("reverse-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    AuthorisationEntity authorisationEntity = mock(AuthorisationEntity.class);
    when(authorisationEntity.getId()).thenReturn(authorisationId);
    when(authorisationEntity.getAccountId()).thenReturn(accountId);
    when(authorisationEntity.getAmount()).thenReturn(BigDecimal.TEN);
    when(authorisationEntity.getCurrencyCode()).thenReturn("USD");
    when(authorisationEntity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(authorisationRepository.findById(authorisationId))
        .thenReturn(Optional.of(authorisationEntity));

    AccountEntity accountEntity = mockExistingAccountEntity();
    accountEntity.setId(accountId);
    accountEntity.setCurrencyCode("USD");
    accountEntity.setStatus(AccountStatus.ACTIVE);
    accountEntity.setAvailableBalance(new BigDecimal("90.00"));
    accountEntity.setReservedBalance(new BigDecimal("10.00"));
    accountEntity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    accountEntity.setUpdatedAt(OffsetDateTime.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountEntity));

    DataIntegrityViolationException cause = new DataIntegrityViolationException("duplicate key");
    when(authorisationRepository.saveAndFlush(authorisationEntity)).thenThrow(cause);

    ConcurrentIdempotencyRaceException exception =
        assertThrows(
            ConcurrentIdempotencyRaceException.class,
            () ->
                executor.reverseInTransaction(authorisationId, reverseRequest, UUID.randomUUID()));

    assertThat(exception.getCause()).isEqualTo(cause);
    verify(outboxEventService, never())
        .enqueueAuthorisation(
            any(AuthorisationEntity.class),
            any(AuthorisationEventEntity.class),
            any(OperationType.class));
  }

  private AuthorisationEntity existingAuthorisationEntity(UUID accountId, String idempotencyKey) {
    OffsetDateTime now = OffsetDateTime.now();
    AuthorisationEntity entity = mock(AuthorisationEntity.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());
    when(entity.getAccountId()).thenReturn(accountId);
    when(entity.getAmount()).thenReturn(BigDecimal.TEN);
    when(entity.getCurrencyCode()).thenReturn("USD");
    when(entity.getMerchantReference()).thenReturn("reference");
    when(entity.getStatus()).thenReturn(AuthorisationStatus.AUTHORISED);
    when(entity.getCreatedAt()).thenReturn(now);
    when(entity.getUpdatedAt()).thenReturn(now);
    return entity;
  }

  private AuthorisationResponse authorise(AuthorisationRequest request, String normalizedCurrency) {
    return executor.authoriseInTransaction(
        request,
        normalizedCurrency,
        FraudDecision.approve(0, java.util.List.of()),
        UUID.randomUUID());
  }

  private AccountEntity mockExistingAccountEntity() {
    return new TestAccountEntity();
  }

  private static class TestAccountEntity extends AccountEntity {}
}
