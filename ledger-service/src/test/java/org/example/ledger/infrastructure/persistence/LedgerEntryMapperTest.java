package org.example.ledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.ledger.api.AuthenticationResponse;
import org.example.ledger.domain.AuthorisationAuthorisedPayload;
import org.example.ledger.domain.AuthorisationCapturedPayload;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class LedgerEntryMapperTest {

  private final LedgerEntryMapper mapper = Mappers.getMapper(LedgerEntryMapper.class);

  @Test
  void shouldConvertToEventLogEntity() {
    EventMetadata metadata = sampleMetadata();
    Map<String, Object> payload = Map.of("status", "AUTHORISED");

    LedgerEventLogEntity entity = mapper.toEventLogEntity(metadata, payload);

    assertThat(entity.getEventId()).isEqualTo(metadata.eventId());
    assertThat(entity.getAggregateType()).isEqualTo(metadata.aggregateType());
    assertThat(entity.getAggregateId()).isEqualTo(metadata.aggregateId());
    assertThat(entity.getEventType()).isEqualTo(metadata.eventType());
    assertThat(entity.getPayload()).isEqualTo(payload);
    assertThat(entity.getCorrelationId()).isEqualTo(metadata.correlationId());
    assertThat(entity.getOccurredAt()).isEqualTo(metadata.occurredAt());
    assertThat(entity.getReceivedAt()).isNotNull();
  }

  @Test
  void shouldConvertToLedgerEntity() {
    EventMetadata metadata = sampleMetadata();
    Map<String, Object> payloadJson = Map.of("status", "AUTHORISED", "currencyCode", "GBP");
    AuthorisationAuthorisedPayload payload =
        new AuthorisationAuthorisedPayload(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "idem-1",
            "merchant-1",
            new BigDecimal("10.00"),
            "GBP",
            "AUTHORISED",
            OffsetDateTime.parse("2026-07-14T10:00:00Z"));

    LedgerEntryEntity entity = mapper.toLedgerEntry(metadata, payload, payloadJson);

    assertThat(entity.getEventId()).isEqualTo(metadata.eventId());
    assertThat(entity.getAggregateType()).isEqualTo(metadata.aggregateType());
    assertThat(entity.getAggregateId()).isEqualTo(metadata.aggregateId());
    assertThat(entity.getAccountId()).isEqualTo(payload.accountId());
    assertThat(entity.getAuthorisationId()).isEqualTo(payload.authorisationId());
    assertThat(entity.getEventType()).isEqualTo(EventType.AUTHORISATION_AUTHORISED.name());
    assertThat(entity.getEntryStatus()).isEqualTo(payload.status());
    assertThat(entity.getAmount()).isEqualByComparingTo(payload.amount());
    assertThat(entity.getCurrencyCode()).isEqualTo(payload.currencyCode());
    assertThat(entity.getMerchantReference()).isEqualTo(payload.merchantReference());
    assertThat(entity.getIdempotencyKey()).isEqualTo(payload.idempotencyKey());
    assertThat(entity.getOccurredAt()).isEqualTo(metadata.occurredAt());
    assertThat(entity.getCreatedAt()).isNotNull();
    assertThat(entity.getPayload()).isEqualTo(payloadJson);
  }

  @Test
  void shouldConvertCapturedPayloadToLedgerEntity() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "AUTHORISATION",
            UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f3"),
            EventType.AUTHORISATION_CAPTURED.name(),
            OffsetDateTime.parse("2026-07-14T10:01:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000011"));
    Map<String, Object> payloadJson = Map.of("status", "CAPTURED", "currencyCode", "GBP");
    AuthorisationCapturedPayload payload =
        new AuthorisationCapturedPayload(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            new BigDecimal("10.00"),
            "GBP",
            "capture-idem-1",
            "CAPTURED",
            OffsetDateTime.parse("2026-07-14T10:01:00Z"));

    LedgerEntryEntity entity = mapper.toLedgerEntry(metadata, payload, payloadJson);

    assertThat(entity.getEventType()).isEqualTo(EventType.AUTHORISATION_CAPTURED.name());
    assertThat(entity.getAuthorisationId()).isEqualTo(payload.authorisationId());
    assertThat(entity.getAccountId()).isEqualTo(payload.accountId());
    assertThat(entity.getAmount()).isEqualByComparingTo("10.00");
    assertThat(entity.getMerchantReference()).isNull();
    assertThat(entity.getIdempotencyKey()).isEqualTo(payload.idempotencyKey());
    assertThat(entity.getPayload()).isEqualTo(payloadJson);
  }

  @Test
  void shouldConvertToAuthorisationResponse() {
    UUID authorisationId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID eventId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-14T10:00:00Z");

    LedgerEntryEntity entity =
        new LedgerEntryEntity(
            UUID.randomUUID(),
            eventId,
            "AUTHORISATION",
            authorisationId,
            accountId,
            authorisationId,
            EventType.AUTHORISATION_AUTHORISED.name(),
            "AUTHORISED",
            new BigDecimal("10.00"),
            "GBP",
            "merchant-1",
            "idem-1",
            createdAt,
            createdAt,
            Map.of("status", "AUTHORISED"));

    AuthenticationResponse response = mapper.toAuthorisationResponse(entity);

    assertThat(response.authorisationId()).isEqualTo(authorisationId);
    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.merchantReference()).isEqualTo("merchant-1");
    assertThat(response.amount()).isEqualByComparingTo("10.00");
    assertThat(response.currencyCode()).isEqualTo("GBP");
    assertThat(response.status()).isEqualTo("AUTHORISED");
    assertThat(response.createdAt()).isEqualTo(createdAt);
    assertThat(response.sourceEventId()).isEqualTo(eventId);
  }

  private static EventMetadata sampleMetadata() {
    return new EventMetadata(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "AUTHORISATION",
        UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f2"),
        EventType.AUTHORISATION_AUTHORISED.name(),
        OffsetDateTime.parse("2026-07-14T10:00:00Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000010"));
  }
}
