package org.example.auth.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.example.auth.account.api.AccountResponse;
import org.example.auth.account.api.CreateAccountRequest;
import org.example.auth.account.api.DepositRequest;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.account.infrastructure.AccountMapper;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.common.exception.AccountNotFoundException;
import org.example.auth.common.exception.CurrencyMismatchException;
import org.example.auth.common.exception.ErrorCode;
import org.example.auth.common.exception.InvalidCurrencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

  @Mock private AccountRepository accountRepository;
  @Spy private AccountMapper accountMapper = Mappers.getMapper(AccountMapper.class);

  @InjectMocks AccountServiceImpl accountService;

  @Test
  void shouldCreateAccount() {
    AccountResponse response = accountService.createAccount(new CreateAccountRequest("gbp"));

    assertThat(response).isNotNull();
    assertThat(response.currencyCode()).isEqualTo("GBP");
    assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(response.availableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.reservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(accountRepository, times(1)).save(any(AccountEntity.class));
  }

  @Test
  void shouldNotCreateAccountWithInvalidRequest() {
    assertThatThrownBy(() -> accountService.createAccount(new CreateAccountRequest("abc")))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currency");

    verify(accountRepository, never()).save(any(AccountEntity.class));
  }

  @Test
  void shouldFindExistingAccount() {
    UUID accountId = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
    OffsetDateTime updatedAt = OffsetDateTime.now();
    AccountEntity entity = new TestAccountEntity();
    entity.setId(accountId);
    entity.setCurrencyCode("GBP");
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setAvailableBalance(BigDecimal.valueOf(55));
    entity.setReservedBalance(BigDecimal.valueOf(5));
    entity.setCreatedAt(createdAt);
    entity.setUpdatedAt(updatedAt);

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(entity));

    AccountResponse response = accountService.getAccountById(accountId);

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.currencyCode()).isEqualTo("GBP");
    assertThat(response.availableBalance()).isEqualByComparingTo("55");
    assertThat(response.reservedBalance()).isEqualByComparingTo("5");
    assertThat(response.createdAt()).isEqualTo(createdAt);
    assertThat(response.updatedAt()).isEqualTo(updatedAt);
    verify(accountRepository, times(1)).findById(accountId);
  }

  @Test
  void shouldFailIfAccountNotExist() {
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.getAccountById(accountId))
        .isInstanceOf(AccountNotFoundException.class)
        .hasMessageContaining("Account not found")
        .satisfies(
            ex -> {
              AccountNotFoundException e = (AccountNotFoundException) ex;
              assertThat(e.getAccountId()).isEqualTo(accountId);
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
            });
    verify(accountRepository, times(1)).findById(accountId);
  }

  @Test
  void shouldDepositAccount() {
    UUID accountId = UUID.randomUUID();
    DepositRequest request = new DepositRequest(BigDecimal.TEN, "GBP");
    OffsetDateTime originalUpdatedAt = OffsetDateTime.now().minusHours(1);

    AccountEntity entity = new TestAccountEntity();
    entity.setId(accountId);
    entity.setCurrencyCode("GBP");
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setAvailableBalance(BigDecimal.ZERO);
    entity.setReservedBalance(BigDecimal.ZERO);
    entity.setCreatedAt(OffsetDateTime.now().minusDays(1L));
    entity.setUpdatedAt(originalUpdatedAt);

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(entity));

    AccountResponse response = accountService.deposit(accountId, request);
    assertThat(response).isNotNull();
    assertThat(response.currencyCode()).isEqualTo("GBP");
    assertThat(response.availableBalance()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(response.reservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.updatedAt()).isAfterOrEqualTo(originalUpdatedAt);

    assertThat(entity.getAvailableBalance()).isEqualByComparingTo(BigDecimal.TEN);
    assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
    verify(accountRepository, times(1)).findById(accountId);
    verify(accountRepository, never()).save(any(AccountEntity.class));
  }

  @Test
  void shouldNotDepositIfAccountNotExist() {
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> accountService.deposit(accountId, new DepositRequest(BigDecimal.ONE, "GBP")))
        .isInstanceOf(AccountNotFoundException.class)
        .satisfies(
            ex -> {
              AccountNotFoundException e = (AccountNotFoundException) ex;
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
              assertThat(e.getAccountId()).isEqualTo(accountId);
            });
    verify(accountRepository, times(1)).findById(accountId);
  }

  @Test
  void shouldNotDepositForCurrencyMismatch() {
    UUID accountId = UUID.randomUUID();
    AccountEntity entity = new TestAccountEntity();
    entity.setId(accountId);
    entity.setCurrencyCode("GBP");
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setAvailableBalance(BigDecimal.ZERO);
    entity.setReservedBalance(BigDecimal.ZERO);
    entity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    entity.setUpdatedAt(OffsetDateTime.now());

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(entity));

    assertThatThrownBy(
            () -> accountService.deposit(accountId, new DepositRequest(BigDecimal.ONE, "USD")))
        .isInstanceOf(CurrencyMismatchException.class)
        .satisfies(
            ex -> {
              CurrencyMismatchException e = (CurrencyMismatchException) ex;
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
              assertThat(e.getExpected()).isEqualTo("GBP");
              assertThat(e.getProvided()).isEqualTo("USD");
            });
  }

  @Test
  void shouldNotDepositForInvalidRequest() {
    UUID accountId = UUID.randomUUID();
    AccountEntity entity = new TestAccountEntity();
    entity.setId(accountId);
    entity.setCurrencyCode("GBP");
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setAvailableBalance(BigDecimal.ZERO);
    entity.setReservedBalance(BigDecimal.ZERO);
    entity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    entity.setUpdatedAt(OffsetDateTime.now());

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(entity));

    assertThatThrownBy(
            () -> accountService.deposit(accountId, new DepositRequest(BigDecimal.ZERO, "GBP")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Amount must be positive");

    assertThatThrownBy(
            () -> accountService.deposit(accountId, new DepositRequest(BigDecimal.ONE, "abc")))
        .isInstanceOf(InvalidCurrencyException.class)
        .satisfies(
            ex ->
                assertThat(((InvalidCurrencyException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CURRENCY));
  }

  private static class TestAccountEntity extends AccountEntity {}
}
