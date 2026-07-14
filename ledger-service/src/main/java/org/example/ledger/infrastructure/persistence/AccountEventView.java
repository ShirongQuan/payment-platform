package org.example.ledger.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface AccountEventView {
  UUID getEventId();

  String getEventType();

  UUID getAggregateId();

  OffsetDateTime getOccurredAt();

  BigDecimal getAmount();

  String getCurrencyCode();
}
