package org.example.ledger.infrastructure;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.springframework.stereotype.Component;

@Component
public class LedgerEntryMapper {

  public LedgerEventLogEntity toEventLogEntity(
      EventMetadata metadata, Map<String, Object> payloadJson) {
    return new LedgerEventLogEntity(
        metadata.eventId(),
        metadata.aggregateType(),
        metadata.aggregateId(),
        metadata.eventType(),
        payloadJson,
        metadata.correlationId(),
        metadata.occurredAt(),
        OffsetDateTime.now());
  }

  public LedgerEntryEntity toLedgerEntry(
      EventMetadata metadata,
      AuthorisationAuthorisedPayload payload,
      Map<String, Object> payloadJson) {
    return new LedgerEntryEntity(
        UUID.randomUUID(),
        metadata.eventId(),
        metadata.aggregateType(),
        metadata.aggregateId(),
        payload.accountId(),
        payload.authorisationId(),
        EventType.AUTHORISATION_AUTHORISED.name(),
        payload.status(),
        payload.amount(),
        payload.currencyCode(),
        payload.merchantReference(),
        payload.idempotencyKey(),
        metadata.occurredAt(),
        OffsetDateTime.now(),
        payloadJson);
  }
}
