package org.example.auth.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.auth.account.domain.Account;
import org.example.auth.account.domain.AccountStatus;
import org.example.auth.common.exception.InvalidCurrencyException;
import org.junit.jupiter.api.Test;

class AccountMapperTest {

  @Test
  void shouldMapDomainAccountToEntity() {
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now().minusDays(1);
    OffsetDateTime updatedAt = OffsetDateTime.now();
    Account account =
        new Account(
            id,
            "USD",
            AccountStatus.ACTIVE,
            BigDecimal.valueOf(100),
            BigDecimal.valueOf(5),
            createdAt,
            updatedAt);

    AccountEntity entity = AccountMapper.toEntity(account);

    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(entity.getCurrencyCode()).isEqualTo("USD");
    assertThat(entity.getAvailableBalance()).isEqualByComparingTo("100");
    assertThat(entity.getReservedBalance()).isEqualByComparingTo("5");
    assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void shouldMapEntityToDomainAccount() {
    UUID id = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.now().minusDays(2);
    OffsetDateTime updatedAt = OffsetDateTime.now().minusHours(1);

    AccountEntity entity = new AccountEntity();
    entity.setId(id);
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setCurrencyCode("usd");
    entity.setAvailableBalance(BigDecimal.valueOf(55));
    entity.setReservedBalance(BigDecimal.valueOf(10));
    entity.setCreatedAt(createdAt);
    entity.setUpdatedAt(updatedAt);

    Account account = AccountMapper.toAccount(entity);

    assertThat(account.getId()).isEqualTo(id);
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getCurrencyCode()).isEqualTo("USD");
    assertThat(account.getAvailableBalance()).isEqualByComparingTo("55");
    assertThat(account.getReservedBalance()).isEqualByComparingTo("10");
    assertThat(account.getCreatedAt()).isEqualTo(createdAt);
    assertThat(account.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void shouldFailWhenMappingEntityWithInvalidCurrencyToDomain() {
    AccountEntity entity = new AccountEntity();
    entity.setId(UUID.randomUUID());
    entity.setStatus(AccountStatus.ACTIVE);
    entity.setCurrencyCode("abc");
    entity.setAvailableBalance(BigDecimal.ZERO);
    entity.setReservedBalance(BigDecimal.ZERO);
    entity.setCreatedAt(OffsetDateTime.now().minusDays(1));
    entity.setUpdatedAt(OffsetDateTime.now());

    assertThatThrownBy(() -> AccountMapper.toAccount(entity))
        .isInstanceOf(InvalidCurrencyException.class)
        .hasMessageContaining("Invalid currencyCode abc");
  }
}
