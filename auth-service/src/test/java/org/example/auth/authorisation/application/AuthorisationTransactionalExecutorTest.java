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
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventRepository;
import org.example.auth.authorisation.infrastructure.AuthorisationMapper;
import org.example.auth.authorisation.infrastructure.AuthorisationRepository;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.IdempotencyConflictException;
import org.example.auth.outbox.application.OutboxEventService;
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

  @InjectMocks private AuthorisationTransactionalExecutor executor;

  @Test
  void shouldReturnExistingAuthRecordIfSameIdempotentRequest() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    AuthorisationEntity existing = existingAuthorisationEntity(accountId, idempotencyKey);

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    AuthorisationResponse response = executor.authoriseInTransaction(request, "USD");

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.idempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(response.amount()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(response.currencyCode()).isEqualTo("USD");
    assertThat(response.status()).isEqualTo(AuthorisationStatus.AUTHORISED);

    verify(accountRepository, never()).findById(any(UUID.class));
    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
  }

  @Test
  void shouldReturnIdempotencyConflictExceptionIfMismatchedIdempotentRequest() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    AuthorisationEntity existing = mock(AuthorisationEntity.class);
    when(existing.getAmount()).thenReturn(BigDecimal.ONE);

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
        .thenReturn(Optional.of(existing));

    assertThatExceptionOfType(IdempotencyConflictException.class)
        .isThrownBy(() -> executor.authoriseInTransaction(request, "USD"));

    verify(accountRepository, never()).findById(any(UUID.class));
    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
  }

  @Test
  void shouldThrowExceptionIfAccountNotFound() {
    UUID accountId = UUID.randomUUID();
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, "key", BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, "key"))
        .thenReturn(Optional.empty());
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    AccountNotFoundException exception =
        assertThrows(
            AccountNotFoundException.class, () -> executor.authoriseInTransaction(request, "USD"));
    assertThat(exception.getAccountId()).isEqualTo(accountId);

    verify(authorisationRepository, never()).saveAndFlush(any(AuthorisationEntity.class));
    verify(outboxEventService, never())
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
  }

  @Test
  void shouldPersistDeclinedAuthIfInsufficientFunds() {
    UUID accountId = UUID.randomUUID();
    String idempotencyKey = "key";
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, idempotencyKey, BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey))
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

    AuthorisationResponse response = executor.authoriseInTransaction(request, "USD");

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
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
  }

  @Test
  void shouldCatchDataIntegrityViolationExceptionAndRethrow() {
    UUID accountId = UUID.randomUUID();
    AuthorisationRequest request =
        new AuthorisationRequest(accountId, "key", BigDecimal.TEN, "USD", "reference");

    when(authorisationRepository.findByAccountIdAndIdempotencyKey(accountId, "key"))
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
        assertThrows(
            ConcurrentIdempotencyRaceException.class,
            () -> executor.authoriseInTransaction(request, "USD"));
    assertThat(ex.getCause()).isEqualTo(cause);

    assertThat(accountEntity.getAvailableBalance()).isEqualByComparingTo("990.00");
    assertThat(accountEntity.getReservedBalance()).isEqualByComparingTo("10.00");
    verify(accountRepository).flush();
    verify(outboxEventService, never())
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
  }

  @Test
  void shouldAuthoriseInTransaction() {
    when(authorisationRepository.findByAccountIdAndIdempotencyKey(
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
    AuthorisationResponse authResponse = executor.authoriseInTransaction(request, "gbp");

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
        .enqueueAuthorisation(any(Authorisation.class), any(AuthorisationEventEntity.class));
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

  private AccountEntity mockExistingAccountEntity() {
    return new TestAccountEntity();
  }

  private static class TestAccountEntity extends AccountEntity {}
}
