package org.example.auth.authorisation.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.outbox.domain.EventType;

/**
 * JPA entity mapped to the {@code authorisation_event} table: an append-only audit trail of every
 * authorisation lifecycle transition (authorised/declined/captured/reversed).
 *
 * <p>The unique constraint on {@code (account_id, event_type, idempotency_key)} is what backs
 * idempotent replay detection for authorise/capture/reverse requests (see {@link
 * AuthorisationEventRepository#findByAccountIdAndEventTypeAndIdempotencyKey}).
 */
@Entity
@Table(name = "authorisation_event")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AuthorisationEventEntity {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "authorisation_id", nullable = false, updatable = false)
  private UUID authorisationId;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(name = "event_type", nullable = false, updatable = false)
  @Enumerated(EnumType.STRING)
  private EventType eventType;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  @Size(max = 30)
  private String idempotencyKey;

  @Column(nullable = false, updatable = false)
  private BigDecimal amount;

  @Column(name = "currency_code", nullable = false, updatable = false, length = 3)
  private String currencyCode;

  @Column(name = "reason_code", updatable = false, nullable = true)
  @Enumerated(EnumType.STRING)
  private AuthorisationEventReason reasonCode;

  @Column(name = "correlation_id", nullable = true, updatable = false)
  private UUID correlationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;
}
