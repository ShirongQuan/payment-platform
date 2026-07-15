package org.example.auth.account.infrastructure;

import org.example.auth.account.domain.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps between the {@link Account} domain model and the {@link AccountEntity} JPA entity.
 *
 * <p>Keeps the domain layer independent of JPA by acting as a translation layer. Only this class
 * should know about both sides.
 */
@Mapper(componentModel = "spring")
public interface AccountMapper {

  /**
   * Converts a domain {@link Account} to a new (unmanaged) {@link AccountEntity} suitable for
   * persisting via the repository.
   */
  @Mapping(target = "version", ignore = true)
  AccountEntity toEntity(Account account);

  /**
   * Rehydrates a {@link Account} domain object from a managed {@link AccountEntity}. Used when
   * business logic needs to operate on a loaded entity inside a transaction.
   */
  default Account toAccount(AccountEntity entity) {
    if (entity == null) {
      return null;
    }
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
