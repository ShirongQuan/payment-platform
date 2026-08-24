package org.example.ledger.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.ledger.api.AuthenticationResponse;
import org.example.ledger.domain.AuthorisationAuthorisedPayload;
import org.example.ledger.domain.AuthorisationCapturedPayload;
import org.example.ledger.domain.AuthorisationReversedPayload;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper translating Kafka event metadata/payloads into ledger persistence entities, and
 * ledger entities back into query API responses.
 *
 * <p>Each {@code toLedgerEntry}/{@code toReversedLedgerEntry} overload corresponds to one
 * authorisation lifecycle event type (authorised/captured/reversed); a fresh {@code entryId} is
 * generated per row since ledger entries are append-only (one row per event, never updated).
 */
@Mapper(
    componentModel = "spring",
    imports = {EventType.class, UUID.class, OffsetDateTime.class})
public interface LedgerEntryMapper {

  /** Maps a raw event (metadata + parsed JSON payload) into the append-only event log entity. */
  @Mapping(target = "eventId", source = "metadata.eventId")
  @Mapping(target = "aggregateType", source = "metadata.aggregateType")
  @Mapping(target = "aggregateId", source = "metadata.aggregateId")
  @Mapping(target = "eventType", source = "metadata.eventType")
  @Mapping(target = "payload", source = "payloadJson")
  @Mapping(target = "correlationId", source = "metadata.correlationId")
  @Mapping(target = "occurredAt", source = "metadata.occurredAt")
  @Mapping(target = "receivedAt", expression = "java(OffsetDateTime.now())")
  LedgerEventLogEntity toEventLogEntity(EventMetadata metadata, Map<String, Object> payloadJson);

  /** Maps an AUTHORISATION_AUTHORISED event into a new ledger entry projection row. */
  @Mapping(target = "entryId", expression = "java(UUID.randomUUID())")

  @Mapping(target = "eventId", source = "metadata.eventId")
  @Mapping(target = "aggregateType", source = "metadata.aggregateType")
  @Mapping(target = "aggregateId", source = "metadata.aggregateId")
  @Mapping(target = "accountId", source = "payload.accountId")
  @Mapping(target = "authorisationId", source = "payload.authorisationId")
  @Mapping(target = "eventType", source = "metadata.eventType")
  @Mapping(target = "entryStatus", source = "payload.status")
  @Mapping(target = "amount", source = "payload.amount")
  @Mapping(target = "currencyCode", source = "payload.currencyCode")
  @Mapping(target = "merchantReference", source = "payload.merchantReference")
  @Mapping(target = "idempotencyKey", source = "payload.idempotencyKey")
  @Mapping(target = "occurredAt", source = "metadata.occurredAt")
  @Mapping(target = "createdAt", expression = "java(OffsetDateTime.now())")
  @Mapping(target = "payload", source = "payloadJson")
  LedgerEntryEntity toLedgerEntry(
      EventMetadata metadata,
      AuthorisationAuthorisedPayload payload,
      Map<String, Object> payloadJson);

  /** Maps an AUTHORISATION_CAPTURED event into a new ledger entry projection row. */
  @Mapping(target = "entryId", expression = "java(UUID.randomUUID())")
  @Mapping(target = "eventId", source = "metadata.eventId")
  @Mapping(target = "aggregateType", source = "metadata.aggregateType")
  @Mapping(target = "aggregateId", source = "metadata.aggregateId")
  @Mapping(target = "accountId", source = "payload.accountId")
  @Mapping(target = "authorisationId", source = "payload.authorisationId")
  @Mapping(target = "eventType", source = "metadata.eventType")
  @Mapping(target = "entryStatus", source = "payload.status")
  @Mapping(target = "amount", source = "payload.amount")
  @Mapping(target = "currencyCode", source = "payload.currencyCode")
  @Mapping(target = "idempotencyKey", source = "payload.idempotencyKey")
  @Mapping(target = "occurredAt", source = "metadata.occurredAt")
  @Mapping(target = "createdAt", expression = "java(OffsetDateTime.now())")
  @Mapping(target = "payload", source = "payloadJson")
  LedgerEntryEntity toLedgerEntry(
      EventMetadata metadata,
      AuthorisationCapturedPayload payload,
      Map<String, Object> payloadJson);

  /** Maps an AUTHORISATION_REVERSED event into a new ledger entry projection row. */
  @Mapping(target = "entryId", expression = "java(UUID.randomUUID())")

  @Mapping(target = "eventId", source = "metadata.eventId")
  @Mapping(target = "aggregateType", source = "metadata.aggregateType")
  @Mapping(target = "aggregateId", source = "metadata.aggregateId")
  @Mapping(target = "accountId", source = "payload.accountId")
  @Mapping(target = "authorisationId", source = "payload.authorisationId")
  @Mapping(target = "eventType", source = "metadata.eventType")
  @Mapping(target = "entryStatus", source = "payload.status")
  @Mapping(target = "amount", source = "payload.amount")
  @Mapping(target = "currencyCode", source = "payload.currencyCode")
  @Mapping(target = "idempotencyKey", source = "payload.idempotencyKey")
  @Mapping(target = "occurredAt", source = "metadata.occurredAt")
  @Mapping(target = "createdAt", expression = "java(OffsetDateTime.now())")
  @Mapping(target = "payload", source = "payloadJson")
  LedgerEntryEntity toReversedLedgerEntry(
      EventMetadata metadata,
      AuthorisationReversedPayload payload,
      Map<String, Object> payloadJson);

  /** Maps a persisted ledger entry row into the public authorisation query API response. */
  @Mapping(target = "authorisationId", source = "entity.authorisationId")

  @Mapping(target = "accountId", source = "entity.accountId")
  @Mapping(target = "merchantReference", source = "entity.merchantReference")
  @Mapping(target = "amount", source = "entity.amount")
  @Mapping(target = "currencyCode", source = "entity.currencyCode")
  @Mapping(target = "status", source = "entity.entryStatus")
  @Mapping(target = "createdAt", source = "entity.createdAt")
  @Mapping(target = "sourceEventId", source = "entity.eventId")
  AuthenticationResponse toAuthorisationResponse(LedgerEntryEntity entity);
}
