package org.example.ledger.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.ledger.application.command.AuthorisationAuthorisedHandler;
import org.example.ledger.common.metrics.LedgerMetrics;
import org.example.ledger.domain.AuthorisationAuthorisedPayload;
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
class AuthorisationAuthorisedHandlerTest {

  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private LedgerEventLogRepository ledgerEventLogRepository;
  @Mock private LedgerEntryRepository ledgerEntryRepository;
  @Mock private LedgerEntryMapper ledgerEntryMapper;
  @Mock private ObjectMapper objectMapper;
  @Mock private LedgerMetrics ledgerMetrics;

  @InjectMocks private AuthorisationAuthorisedHandler handler;

  @Test
  void shouldReturnEarlyWhenEventAlreadyProcessed() {
    EventMetadata metadata = sampleMetadata();

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()), eq(EventType.AUTHORISATION_AUTHORISED.name()), any()))
        .thenReturn(0);

    handler.handle(metadata, "{\"dummy\":true}");

    verify(processedEventRepository)
        .tryInsertProcessedEvent(
            eq(metadata.eventId()), eq(EventType.AUTHORISATION_AUTHORISED.name()), any());
    verify(ledgerMetrics).incrementDuplicateSkipped(EventType.AUTHORISATION_AUTHORISED.name());
    verifyNoInteractions(
        objectMapper, ledgerEntryMapper, ledgerEventLogRepository, ledgerEntryRepository);
  }

  @Test
  void shouldPersistLedgerRowsWhenEventClaimed() {
    EventMetadata metadata = sampleMetadata();
    String rawPayload =
        """
        {
          "authorisationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "accountId":"11111111-1111-1111-1111-111111111111",
          "idempotencyKey":"idem-001",
          "merchantReference":"merchant-001",
          "amount":10.00,
          "currencyCode":"GBP",
          "status":"AUTHORISED",
          "createdAt":"2026-07-13T09:00:00Z",
          "updatedAt":"2026-07-13T09:00:00Z"
        }
        """;

    Map<String, Object> payloadJson = Map.of("status", "AUTHORISED");
    AuthorisationAuthorisedPayload payload =
        new AuthorisationAuthorisedPayload(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "idem-001",
            "merchant-001",
            new BigDecimal("10.00"),
            "GBP",
            "AUTHORISED",
            OffsetDateTime.parse("2026-07-13T09:00:00Z"));

    LedgerEventLogEntity eventLogEntity =
        new LedgerEventLogEntity(
            metadata.eventId(),
            metadata.aggregateType(),
            metadata.aggregateId(),
            metadata.eventType(),
            payloadJson,
            metadata.correlationId(),
            metadata.occurredAt(),
            OffsetDateTime.now());
    LedgerEntryEntity entryEntity =
        new LedgerEntryEntity(
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

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()), eq(EventType.AUTHORISATION_AUTHORISED.name()), any()))
        .thenReturn(1);
    when(objectMapper.readValue(eq(rawPayload), any(TypeReference.class))).thenReturn(payloadJson);
    when(objectMapper.convertValue(payloadJson, AuthorisationAuthorisedPayload.class))
        .thenReturn(payload);
    when(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson)).thenReturn(eventLogEntity);
    when(ledgerEntryMapper.toLedgerEntry(metadata, payload, payloadJson)).thenReturn(entryEntity);

    handler.handle(metadata, rawPayload);

    verify(ledgerEventLogRepository).save(eventLogEntity);
    verify(ledgerEntryRepository).save(entryEntity);
    verify(ledgerEntryMapper).toEventLogEntity(metadata, payloadJson);
    verify(ledgerEntryMapper).toLedgerEntry(metadata, payload, payloadJson);
  }

  @Test
  void shouldThrowIllegalArgumentExceptionWhenPayloadIsMalformed() throws Exception {
    EventMetadata metadata = sampleMetadata();
    String rawPayload = "{not-json}";

    when(processedEventRepository.tryInsertProcessedEvent(
            eq(metadata.eventId()), eq(EventType.AUTHORISATION_AUTHORISED.name()), any()))
        .thenReturn(1);
    when(objectMapper.readValue(eq(rawPayload), any(TypeReference.class)))
        .thenThrow(new RuntimeException("invalid json"));

    assertThrows(IllegalArgumentException.class, () -> handler.handle(metadata, rawPayload));

    verify(processedEventRepository)
        .tryInsertProcessedEvent(
            eq(metadata.eventId()), eq(EventType.AUTHORISATION_AUTHORISED.name()), any());
    verifyNoInteractions(ledgerEntryMapper, ledgerEventLogRepository, ledgerEntryRepository);
  }

  private static EventMetadata sampleMetadata() {
    return new EventMetadata(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "AUTHORISATION",
        UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f2"),
        EventType.AUTHORISATION_AUTHORISED.name(),
        OffsetDateTime.parse("2026-07-13T09:00:00Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000010"));
  }
}
