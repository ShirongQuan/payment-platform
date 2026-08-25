package org.example.auth.authorisation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
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
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AuthorisationNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.fraud.FraudDecision;
import org.example.auth.fraud.FraudOrchestrator;
import org.example.auth.idempotency.CachedAuthorisationResponse;
import org.example.auth.idempotency.CachedCaptureResponse;
import org.example.auth.idempotency.CachedReverseResponse;
import org.example.auth.idempotency.IdempotencyProperties;
import org.example.auth.idempotency.IdempotencyService;
import org.example.auth.outbox.domain.EventType;
import org.example.shared.correlation.CorrelationIdResolver;
import org.example.shared.idempotency.RequestHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorisationServiceImplTest {

  private static final String CLIENT_IP = "203.0.113.10";

  @Mock private AuthorisationTransactionalExecutorImpl transactionalExecutor;
  @Mock private AuthorisationRepository authorisationRepository;
  @Mock private AuthorisationEventRepository authorisationEventRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private FraudOrchestrator fraudOrchestrator;
  @Mock private CorrelationIdResolver correlationIdResolver;
  @Mock private IdempotencyProperties idempotencyProperties;
  @Mock private IdempotencyService idempotencyService;

  private AuthorisationServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new AuthorisationServiceImpl(
            transactionalExecutor,
            authorisationRepository,
            authorisationEventRepository,
            accountRepository,
            fraudOrchestrator,
            correlationIdResolver,
            idempotencyProperties,
            idempotencyService);
    lenient()
        .when(fraudOrchestrator.evaluate(any(), any(), any()))
        .thenReturn(FraudDecision.approve(0, java.util.List.of()));
    lenient().when(correlationIdResolver.resolveOrCreate()).thenReturn(UUID.randomUUID());
    lenient().when(idempotencyProperties.ttl()).thenReturn(Duration.ofHours(24));
    // Default: account exists and is ACTIVE, so the fraud pre-check gate lets requests through.
    // Note: build the mock fully in a separate statement first - nesting a mock's
    // when(...).thenReturn(...) inside the argument list of another when(...).thenReturn(...)
    // confuses Mockito's ongoing-stubbing tracking and throws UnfinishedStubbingException.
    AccountEntity activeAccount = activeAccount();
    lenient().when(accountRepository.findById(any())).thenReturn(Optional.of(activeAccount));
  }

  private static AccountEntity activeAccount() {
    AccountEntity entity = mock(AccountEntity.class);
    lenient().when(entity.getStatus()).thenReturn(AccountStatus.ACTIVE);
    return entity;
  }

  @Test
  void authorise_shouldReturnCachedResponse_withoutHittingRepositoriesOrExecutor() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-cached";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    AuthorisationResponse cachedResponse =
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

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            eq(CachedAuthorisationResponse.class)))
        .thenReturn(
            Optional.of(
                new CachedAuthorisationResponse(fingerprintFor(request, "USD"), cachedResponse)));

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response).isSameAs(cachedResponse);
    verifyNoInteractions(authorisationRepository);
    verifyNoInteractions(authorisationEventRepository);
    verifyNoInteractions(transactionalExecutor);
    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());
  }

  @Test
  void authorise_shouldThrowConflict_whenCachedFingerprintMismatchesRequest() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-cached-conflict";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    AuthorisationResponse cachedResponse =
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

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            eq(CachedAuthorisationResponse.class)))
        .thenReturn(Optional.of(new CachedAuthorisationResponse("mismatch", cachedResponse)));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request, CLIENT_IP));

    verifyNoInteractions(transactionalExecutor);
    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());
  }

  @Test
  void authorise_shouldFallBackToTransaction_whenCachedPayloadIsLegacyWithoutFingerprint() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-legacy-payload";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

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

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            eq(CachedAuthorisationResponse.class)))
        .thenReturn(Optional.of(new CachedAuthorisationResponse(null, null)));
    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response).isSameAs(executorResponse);
  }

  @Test
  void authorise_shouldStoreResponseInCache_afterSuccessfulTransaction() {    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-store-success";
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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response).isSameAs(executorResponse);
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            any(CachedAuthorisationResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void authorise_shouldFallbackToTransaction_whenCacheReadFails() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-cache-read-error";
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

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            eq(CachedAuthorisationResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response).isSameAs(executorResponse);
    verify(transactionalExecutor)
        .authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class));
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            any(CachedAuthorisationResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void authorise_shouldPropagateTransactionFailure_whenCacheReadFails() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-cache-read-and-transaction-failure";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("42.00"), "USD", "merchant-1");

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.AUTHORISE),
            eq(accountId),
            eq(idempotencyKey),
            eq(CachedAuthorisationResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.authorise(request, CLIENT_IP));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());
  }

  @Test
  void authorise_shouldNotStoreInCache_whenTransactionFails() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-store-failure";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("42.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.authorise(request, CLIENT_IP));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());
  }

  @Test
  void authorise_shouldSucceed_whenCacheStoreFailsAfterSuccessfulTransaction() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-cache-store-error";
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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response).isSameAs(executorResponse);
  }

  @Test
  void authorise_shouldReturnResolvedResponse_whenCacheStoreFailsAfterRaceResolution() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-race-cache-store-error";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        existingAuthorisation(accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");
    // First call is the pre-check (before the tx attempt, simulating "not yet committed" at that
    // point); second call is the post-rollback resolution (simulating the concurrent winner has
    // since committed).
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedAuthorisationResponse.class), any());

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.idempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(response.status()).isEqualTo(AuthorisationStatus.AUTHORISED);
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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class),
            anyString(),
            any(PreAuthDecision.class),
            any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        existingAuthorisation(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertEquals(accountId, response.accountId());
    assertEquals(idempotencyKey, response.idempotencyKey());
    assertEquals(AuthorisationStatus.AUTHORISED, response.status());
    verify(authorisationRepository, times(2))
        .findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(accountId, idempotencyKey);
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerPayloadIsDifferent() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-1";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing = conflictingAuthorisation(new BigDecimal("99.00"));

    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request, CLIENT_IP));
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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

    assertThat(response.currencyCode()).isEqualTo("USD");
    verify(transactionalExecutor)
        .authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class));
    verify(fraudOrchestrator).evaluate(any(AuthorisationRequest.class), anyString(), eq(CLIENT_IP));
    // Called once as the best-effort pre-check (before the fraud call); returns empty by default
    // since it's not stubbed, so the request proceeds through the normal transactional path.
    verify(authorisationRepository)
        .findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(accountId, idempotencyKey);
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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenReturn(executorResponse);

    assertThat(service.authorise(request, CLIENT_IP)).isSameAs(executorResponse);
    verify(authorisationRepository)
        .findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(accountId, idempotencyKey);
  }

  @Test
  void authorise_shouldReuseCorrelationIdFromMdc() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-correlation";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");
    UUID correlationId = UUID.randomUUID();

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

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), eq(correlationId)))
        .thenReturn(executorResponse);

    when(correlationIdResolver.resolveOrCreate()).thenReturn(correlationId);

    assertThat(service.authorise(request, CLIENT_IP)).isSameAs(executorResponse);

    verify(fraudOrchestrator).evaluate(any(AuthorisationRequest.class), anyString(), eq(CLIENT_IP));
    verify(transactionalExecutor)
        .authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), eq(correlationId));
  }

  @Test
  void authorise_shouldWriteGeneratedCorrelationIdBackToMdcWhenMissing() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-generated-correlation";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

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

    UUID generatedCorrelationId = UUID.randomUUID();
    when(correlationIdResolver.resolveOrCreate()).thenReturn(generatedCorrelationId);
    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class),
            any(),
            any(PreAuthDecision.class),
            eq(generatedCorrelationId)))
        .thenReturn(executorResponse);

    service.authorise(request, CLIENT_IP);

    verify(correlationIdResolver).resolveOrCreate();
  }

  @Test
  void authorise_shouldThrowIllegalState_whenRaceButNoExistingRowFound() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-missing";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.authorise(request, CLIENT_IP))
        .withMessageContaining("Duplicate idempotency key detected");
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerCurrencyDiffers() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-currency";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing = mock(AuthorisationEntity.class);
    when(existing.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(existing.getCurrencyCode()).thenReturn("EUR");
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request, CLIENT_IP));
  }

  @Test
  void authorise_throwsConflict_whenRaceWinnerMerchantReferenceDiffers() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-merchant";
    AuthorisationRequest request =
        new AuthorisationRequest(
            accountId, idempotencyKey, new BigDecimal("10.00"), "USD", "merchant-1");

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        raceWinnerAuthorisation(new BigDecimal("10.00"), "USD", "merchant-other");
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    assertThrows(IdempotencyConflictException.class, () -> service.authorise(request, CLIENT_IP));
  }

  @Test
  void authorise_acceptsRaceWinner_whenMerchantReferenceIsNullOnBothSides() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "idem-null-merchant";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, new BigDecimal("10.00"), "USD", null);

    when(transactionalExecutor.authoriseInTransaction(
            any(AuthorisationRequest.class), any(), any(PreAuthDecision.class), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));

    AuthorisationEntity existing =
        existingAuthorisation(accountId, idempotencyKey, new BigDecimal("10.00"), "USD", null);
    when(authorisationRepository.findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
            accountId, idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(existing));

    AuthorisationResponse response = service.authorise(request, CLIENT_IP);

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

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(response);

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

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class, () -> service.capture(authorisationId, request));
  }

  @Test
  void capture_shouldReturnCachedResponse_withoutHittingExecutorOrRepositories() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-cached-key");

    CaptureResponse cachedResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedCaptureResponse.class)))
        .thenReturn(
            Optional.of(
                new CachedCaptureResponse(captureFingerprintFor(authorisationId), cachedResponse)));

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response).isSameAs(cachedResponse);
    verifyNoInteractions(transactionalExecutor);
    verify(authorisationRepository, never()).findById(any());
    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedCaptureResponse.class), any());
  }

  @Test
  void capture_shouldThrowConflict_whenCachedFingerprintMismatchesRequest() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-conflict-key");

    CaptureResponse cachedResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedCaptureResponse.class)))
        .thenReturn(Optional.of(new CachedCaptureResponse("mismatch", cachedResponse)));

    assertThrows(
        IdempotencyConflictException.class, () -> service.capture(authorisationId, request));

    verifyNoInteractions(transactionalExecutor);
  }

  @Test
  void capture_shouldStoreResponseInCache_afterSuccessfulTransaction() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-store-key");

    CaptureResponse executorResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            any(CachedCaptureResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void capture_shouldNotStoreInCache_whenTransactionFails() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-failure-key");

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.capture(authorisationId, request));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedCaptureResponse.class), any());
  }

  @Test
  void capture_shouldFallbackToTransaction_whenCacheReadFails() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-cache-read-error-key");

    CaptureResponse executorResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedCaptureResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));
    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            any(CachedCaptureResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void capture_shouldPropagateTransactionFailure_whenCacheReadAlsoFails() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-cache-read-and-tx-failure-key");

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedCaptureResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));
    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.capture(authorisationId, request));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedCaptureResponse.class), any());
  }

  @Test
  void capture_shouldSucceed_whenCacheStoreFailsAfterSuccessfulTransaction() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-store-error-key");

    CaptureResponse executorResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);
    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedCaptureResponse.class), any());

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
  }

  @Test
  void capture_shouldReturnResolvedResponse_whenCacheStoreFailsAfterRaceResolution() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-race-store-error-key");

    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedCaptureResponse.class), any());

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.status()).isEqualTo(AuthorisationStatus.CAPTURED);
  }

  @Test
  void capture_shouldFallBackToTransaction_whenCachedPayloadIsLegacyWithoutFingerprint() {
    UUID authorisationId = UUID.randomUUID();
    CaptureRequest request = new CaptureRequest("capture-legacy-payload-key");

    CaptureResponse executorResponse =
        new CaptureResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.CAPTURED,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.CAPTURE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedCaptureResponse.class)))
        .thenReturn(Optional.of(new CachedCaptureResponse(null, null)));
    when(transactionalExecutor.captureInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    CaptureResponse response = service.capture(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
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

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(response);

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

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new ConcurrentIdempotencyRaceException(new RuntimeException("duplicate key")));
    when(authorisationRepository.findById(authorisationId)).thenReturn(Optional.empty());

    assertThrows(
        AuthorisationNotFoundException.class, () -> service.reverse(authorisationId, request));
  }

  @Test
  void reverse_shouldReturnCachedResponse_withoutHittingExecutorOrRepositories() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-cached-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse cachedResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedReverseResponse.class)))
        .thenReturn(
            Optional.of(
                new CachedReverseResponse(
                    reverseFingerprintFor(authorisationId, request.reasonCode()), cachedResponse)));

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response).isSameAs(cachedResponse);
    verifyNoInteractions(transactionalExecutor);
    verify(authorisationRepository, never()).findById(any());
    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedReverseResponse.class), any());
  }

  @Test
  void reverse_shouldThrowConflict_whenCachedReasonCodeDiffersFromRequest() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-conflict-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse cachedResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.FRAUD_DECLINED,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedReverseResponse.class)))
        .thenReturn(
            Optional.of(
                new CachedReverseResponse(
                    reverseFingerprintFor(authorisationId, AuthorisationEventReason.FRAUD_DECLINED),
                    cachedResponse)));

    assertThrows(
        IdempotencyConflictException.class, () -> service.reverse(authorisationId, request));

    verifyNoInteractions(transactionalExecutor);
  }

  @Test
  void reverse_shouldStoreResponseInCache_afterSuccessfulTransaction() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-store-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse executorResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            any(CachedReverseResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void reverse_shouldNotStoreInCache_whenTransactionFails() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-failure-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.reverse(authorisationId, request));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedReverseResponse.class), any());
  }

  @Test
  void reverse_shouldFallbackToTransaction_whenCacheReadFails() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest(
            "reverse-cache-read-error-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse executorResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedReverseResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));
    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
    verify(idempotencyService)
        .store(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            any(CachedReverseResponse.class),
            eq(Duration.ofHours(24)));
  }

  @Test
  void reverse_shouldPropagateTransactionFailure_whenCacheReadAlsoFails() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest(
            "reverse-cache-read-and-tx-failure-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedReverseResponse.class)))
        .thenThrow(new RuntimeException("redis unavailable"));
    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenThrow(new RuntimeException("transaction failed"));

    assertThrows(RuntimeException.class, () -> service.reverse(authorisationId, request));

    verify(idempotencyService, never())
        .store(any(), any(UUID.class), anyString(), any(CachedReverseResponse.class), any());
  }

  @Test
  void reverse_shouldSucceed_whenCacheStoreFailsAfterSuccessfulTransaction() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-store-error-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse executorResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);
    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedReverseResponse.class), any());

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
  }

  @Test
  void reverse_shouldReturnResolvedResponse_whenCacheStoreFailsAfterRaceResolution() {
    UUID authorisationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-race-store-error-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
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

    doThrow(new RuntimeException("redis unavailable"))
        .when(idempotencyService)
        .store(any(), any(UUID.class), anyString(), any(CachedReverseResponse.class), any());

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.status()).isEqualTo(AuthorisationStatus.REVERSED);
  }

  @Test
  void reverse_shouldFallBackToTransaction_whenCachedPayloadIsLegacyWithoutFingerprint() {
    UUID authorisationId = UUID.randomUUID();
    ReverseRequest request =
        new ReverseRequest("reverse-legacy-payload-key", AuthorisationEventReason.CUSTOMER_REQUEST);

    ReverseResponse executorResponse =
        new ReverseResponse(
            authorisationId,
            request.idempotencyKey(),
            new BigDecimal("10.00"),
            "USD",
            AuthorisationStatus.REVERSED,
            AuthorisationEventReason.CUSTOMER_REQUEST,
            OffsetDateTime.now());

    when(idempotencyService.get(
            eq(org.example.auth.common.OperationType.REVERSE),
            eq(authorisationId),
            eq(request.idempotencyKey()),
            eq(CachedReverseResponse.class)))
        .thenReturn(Optional.of(new CachedReverseResponse(null, null)));
    when(transactionalExecutor.reverseInTransaction(
            eq(authorisationId), eq(request), any(UUID.class)))
        .thenReturn(executorResponse);

    ReverseResponse response = service.reverse(authorisationId, request);

    assertThat(response).isSameAs(executorResponse);
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

  private static String fingerprintFor(AuthorisationRequest request, String normalizedCurrency) {
    String canonicalRequest =
        RequestHashing.canonicalJoin(
            request.accountId().toString(),
            request.amount().stripTrailingZeros().toPlainString(),
            normalizedCurrency,
            request.merchantReference());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
  }

  private static String captureFingerprintFor(UUID authorisationId) {
    String canonicalRequest = RequestHashing.canonicalJoin(authorisationId.toString());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
  }

  private static String reverseFingerprintFor(
      UUID authorisationId, AuthorisationEventReason reasonCode) {
    String canonicalRequest =
        RequestHashing.canonicalJoin(
            authorisationId.toString(), reasonCode == null ? null : reasonCode.name());
    return "v1:" + RequestHashing.sha256Hex(canonicalRequest);
  }
}
