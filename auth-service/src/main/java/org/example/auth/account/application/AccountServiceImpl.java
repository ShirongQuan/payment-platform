package org.example.auth.account.application;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.account.api.AccountResponse;
import org.example.auth.account.api.CreateAccountRequest;
import org.example.auth.account.api.DepositRequest;
import org.example.auth.account.domain.Account;
import org.example.auth.account.infrastructure.AccountEntity;
import org.example.auth.account.infrastructure.AccountMapper;
import org.example.auth.account.infrastructure.AccountRepository;
import org.example.auth.common.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link AccountService}.
 *
 * <p>Each method runs in its own transaction. Write methods roll back on any exception; read
 * methods use a read-only transaction for performance optimisation.
 *
 * <p>For write operations the pattern is: load managed entity → convert to domain object → apply
 * business logic → write changed fields back to entity → rely on JPA dirty-checking to flush on
 * commit. This avoids the {@code DuplicateKeyException} that would occur if a new entity were
 * constructed with the same ID as an already-managed one.
 */
@Service
public class AccountServiceImpl implements AccountService {

  private final AccountRepository accountRepository;
  private final AccountMapper accountMapper;

  public AccountServiceImpl(AccountRepository accountRepository, AccountMapper accountMapper) {
    this.accountRepository = accountRepository;
    this.accountMapper = accountMapper;
  }

  /**
   * Persists a new account. The domain object owns creation logic (UUID, status, zero balances);
   * this method translates the result to a JPA entity and saves it.
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public AccountResponse createAccount(CreateAccountRequest createAccountRequest) {
    Account account = new Account(createAccountRequest.currencyCode());

    accountRepository.save(accountMapper.toEntity(account));

    return new AccountResponse(
        account.getId(),
        account.getStatus(),
        account.getCurrencyCode(),
        account.getAvailableBalance(),
        account.getReservedBalance(),
        account.getCreatedAt(),
        account.getUpdatedAt());
  }

  /**
   * Loads and returns an account by ID. Uses a read-only transaction to signal that no writes
   * should occur, allowing the JPA provider to skip dirty-checking on flush.
   */
  @Override
  @Transactional(readOnly = true)
  public AccountResponse getAccountById(UUID accountId) {
    AccountEntity account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

    return new AccountResponse(
        accountId,
        account.getStatus(),
        account.getCurrencyCode(),
        account.getAvailableBalance(),
        account.getReservedBalance(),
        account.getCreatedAt(),
        account.getUpdatedAt());
  }

  /**
   * Deposits an amount into the account's available balance.
   *
   * <p>Loads the managed entity, converts to domain object, validates currency match, runs domain
   * logic, writes updated fields back to the managed entity. Calls {@code flush()} so that
   * {@code @UpdateTimestamp} is assigned before building the response, ensuring the returned {@code
   * updatedAt} matches the persisted value.
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public AccountResponse deposit(UUID accountId, DepositRequest depositRequest) {
    // Fetch entity (attached to persistence context)
    AccountEntity entity =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

    // Convert to business object for domain logic
    Account account = accountMapper.toAccount(entity);

    // Execute business logic
    account.deposit(depositRequest.amount(), depositRequest.currencyCode());

    // Update the EXISTING entity with modified values (don't create a new one)
    entity.setAvailableBalance(account.getAvailableBalance());
    entity.setReservedBalance(account.getReservedBalance());
    entity.setUpdatedAt(OffsetDateTime.now());

    // Save the SAME entity instance (Hibernate just persists changes)

    return new AccountResponse(
        entity.getId(),
        entity.getStatus(),
        entity.getCurrencyCode(),
        entity.getAvailableBalance(),
        entity.getReservedBalance(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
