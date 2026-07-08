package org.example.auth.account.infrastructure;

import org.example.auth.account.domain.Account;

/**
 * Maps between the {@link Account} domain model and the {@link AccountEntity} JPA entity.
 *
 * <p>Keeps the domain layer independent of JPA by acting as a translation layer. Only this class
 * should know about both sides.
 */
public class AccountMapper {

  /**
   * Converts a domain {@link Account} to a new (unmanaged) {@link AccountEntity} suitable for
   * persisting via the repository.
   */
  public static AccountEntity toEntity(Account account) {
    AccountEntity entity = new AccountEntity();
    entity.setId(account.getId());
    entity.setStatus(account.getStatus());
    entity.setCurrencyCode(account.getCurrencyCode());
    entity.setAvailableBalance(account.getAvailableBalance());
    entity.setReservedBalance(account.getReservedBalance());
    entity.setCreatedAt(account.getCreatedAt());
    entity.setUpdatedAt(account.getUpdatedAt());
    return entity;
  }

  /**
   * Rehydrates a {@link Account} domain object from a managed {@link AccountEntity}. Used when
   * business logic needs to operate on a loaded entity inside a transaction.
   */
  public static Account toAccount(AccountEntity entity) {
    return new Account(
        entity.getId(),
        entity.getCurrencyCode(),
        entity.getStatus(),
        entity.getAvailableBalance(),
        entity.getReservedBalance(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
