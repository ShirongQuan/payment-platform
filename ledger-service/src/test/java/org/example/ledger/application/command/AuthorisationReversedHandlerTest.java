package org.example.ledger.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.ledger.domain.AuthorisationReversedPayload;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.example.ledger.infrastructure.persistence.LedgerEntryEntity;
import org.example.ledger.infrastructure.persistence.LedgerEntryMapper;
import org.example.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.example.ledger.infrastructure.persistence.LedgerEventLogEntity;
import org.example.ledger.infrastructure.persistence.LedgerEventLogRepository;
import org.example.ledger.infrastructure.persistence.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuthorisationReversedHandlerTest {

  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private LedgerEventLogRepository ledgerEventLogRepository;
  @Mock private LedgerEntryRepository ledgerEntryRepository;
  @Mock private LedgerEntryMapper ledgerEntryMapper;
  @Mock private ObjectMapper objectMapper;

  @InjectMocks private AuthorisationReversedHandler handler;

  @Test
  void shouldPersistLedgerProjectionWhenProcessingFirstTimeReverseEvent() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000099"),
            "AUTHORISATION",
            UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f9"),
            EventType.AUTHORISATION_REVERSED.name(),
            OffsetDateTime.parse("2026-07-14T10:02:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000013"));
    String rawPayload =
        """
        {
          "authorisationId":"cccccccc-cccc-cccc-cccc-cccccccccccc",
          "accountId":"33333333-3333-3333-3333-333333333333",
          "amount":10.00,
          "currencyCode":"GBP",
          "idempotencyKey":"reverse-idem-1",
          "status":"REVERSED",
          "reversedAt":"2026-07-14T10:02:00Z",
          "reasonCode":"CUSTOMER_REQUEST"
        }
        """;

    Map<String, Object> payloadJson =
        Map.of(
            "status", "REVERSED",
            "currencyCode", "GBP",
            "reasonCode", "CUSTOMER_REQUEST",
            "idempotencyKey", "reverse-idem-1");
    AuthorisationReversedPayload payload =
        new AuthorisationReversedPayload(
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            new BigDecimal("10.00"),
            "GBP",
            "reverse-idem-1",
            "REVERSED",
            OffsetDateTime.parse("2026-07-14T10:02:00Z"),
            "CUSTOMER_REQUEST");

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()),
            eq(EventType.AUTHORISATION_REVERSED.name()),
            any(OffsetDateTime.class)))
        .thenReturn(1);
    when(objectMapper.readValue(any(String.class), any(TypeReference.class))).thenReturn(payloadJson);
    when(objectMapper.convertValue(payloadJson, AuthorisationReversedPayload.class)).thenReturn(payload);

    LedgerEventLogEntity eventLogEntity = mock(LedgerEventLogEntity.class);
    LedgerEntryEntity ledgerEntryEntity = mock(LedgerEntryEntity.class);
    when(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson)).thenReturn(eventLogEntity);
    when(ledgerEntryMapper.toReversedLedgerEntry(metadata, payload, payloadJson))
        .thenReturn(ledgerEntryEntity);

    handler.handle(metadata, rawPayload);

    verify(ledgerEventLogRepository).save(eventLogEntity);
    verify(ledgerEntryRepository).save(ledgerEntryEntity);
  }

  @Test
  void shouldSkipWhenReverseEventAlreadyProcessed() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.randomUUID(),
            "AUTHORISATION",
            UUID.randomUUID(),
            EventType.AUTHORISATION_REVERSED.name(),
            OffsetDateTime.parse("2026-07-14T10:02:00Z"),
            UUID.randomUUID());

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()),
            eq(EventType.AUTHORISATION_REVERSED.name()),
            any(OffsetDateTime.class)))
        .thenReturn(0);

    handler.handle(metadata, "{\"status\":\"REVERSED\"}");

    verify(ledgerEventLogRepository, never()).save(any());
    verify(ledgerEntryRepository, never()).save(any());
  }

  @Test
  void shouldThrowIllegalArgumentExceptionForMalformedPayload() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.randomUUID(),
            "AUTHORISATION",
            UUID.randomUUID(),
            EventType.AUTHORISATION_REVERSED.name(),
            OffsetDateTime.parse("2026-07-14T10:02:00Z"),
            UUID.randomUUID());

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()),
            eq(EventType.AUTHORISATION_REVERSED.name()),
            any(OffsetDateTime.class)))
        .thenReturn(1);
    when(objectMapper.readValue(any(String.class), any(TypeReference.class)))
        .thenThrow(new RuntimeException("invalid json"));

    assertThatThrownBy(() -> handler.handle(metadata, "not-json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid AUTHORISATION_REVERSED payload");

    verify(ledgerEventLogRepository, never()).save(any());
    verify(ledgerEntryRepository, never()).save(any());
  }
}





