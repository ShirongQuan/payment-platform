package org.example.auth.account.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.auth.account.domain.AccountStatus;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity mapped to the {@code account} table.
 *
 * <p>This is a persistence-layer representation of an account. Business logic should not live here;
 * use the {@link org.example.auth.account.domain.Account} domain model instead.
 *
 * <p>{@code updatedAt} is automatically refreshed by Hibernate via {@link
 * org.hibernate.annotations.UpdateTimestamp} on every flush. {@code version} is used for optimistic
 * locking to prevent lost updates.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountEntity {

  @Id
  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID id;

  /** Optimistic locking version incremented by Hibernate on each UPDATE. */
  @Version private Long version;

  @Column(name = "account_status", nullable = false)
  @Enumerated(EnumType.STRING) // stored as text (e.g. "ACTIVE"), not ordinal, for DB readability
  private AccountStatus status;

  @Column(name = "currency_code", nullable = false, updatable = false, length = 3)
  private String currencyCode;

  @Column(name = "available_balance", nullable = false)
  private BigDecimal availableBalance;

  @Column(name = "reserved_balance", nullable = false)
  private BigDecimal reservedBalance;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Automatically set to the current timestamp by Hibernate on every flush. */
  @Column(name = "updated_at", nullable = false)
  @UpdateTimestamp
  private OffsetDateTime updatedAt;
}
