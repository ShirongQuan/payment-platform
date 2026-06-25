package org.example.authservice.authorisation.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.authservice.authorisation.AuthorisationStatus;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "authorisation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthorisationEntity {

  @Id
  @Column(name = "authorisation_id", nullable = false, updatable = false)
  private UUID id;

  /** Optimistic locking version incremented by Hibernate on each UPDATE. */
  @Version private Long version;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  @Size(max = 20)
  private String idempotencyKey;

  @Column(nullable = false)
  private BigDecimal amount;

  @Column(name = "currency_code", nullable = false, updatable = false, length = 3)
  private String currencyCode;

  @Column(name = "merchant_reference")
  @Size(max = 20)
  private String merchantReference;

  @Column(name = "authorisation_status", nullable = false)
  @Enumerated(EnumType.STRING)
  private AuthorisationStatus status;

  @Column(name = "failure_reason", nullable = false)
  @Size(max = 30)
  private String failureReason;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  @UpdateTimestamp
  private OffsetDateTime updatedAt;
}
