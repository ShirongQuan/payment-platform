package org.example.ledger.application.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerEventProcessorTest {

  @Mock private AuthorisationAuthorisedHandler authorisationAuthorisedHandler;
  @Mock private AuthorisationCapturedHandler authorisationCapturedHandler;

  @InjectMocks private LedgerEventProcessor ledgerEventProcessor;

  @Test
  void shouldProcessEventWithCorrectHandler() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.randomUUID(),
            "AUTHORISATION",
            UUID.randomUUID(),
            EventType.AUTHORISATION_AUTHORISED.name(),
            OffsetDateTime.parse("2026-07-14T09:00:00Z"),
            UUID.randomUUID());

    ledgerEventProcessor.process(metadata, "{\"status\":\"AUTHORISED\"}");

    verify(authorisationAuthorisedHandler).handle(metadata, "{\"status\":\"AUTHORISED\"}");
  }

  @Test
  void shouldProcessCapturedEventWithCorrectHandler() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.randomUUID(),
            "AUTHORISATION",
            UUID.randomUUID(),
            EventType.AUTHORISATION_CAPTURED.name(),
            OffsetDateTime.parse("2026-07-14T09:00:00Z"),
            UUID.randomUUID());

    ledgerEventProcessor.process(metadata, "{\"status\":\"CAPTURED\"}");

    verify(authorisationCapturedHandler).handle(metadata, "{\"status\":\"CAPTURED\"}");
  }

  @Test
  void shouldFailWithUnsupportedEventType() {
    EventMetadata metadata =
        new EventMetadata(
            UUID.randomUUID(),
            "AUTHORISATION",
            UUID.randomUUID(),
            "NOT_EXIST_TYPE",
            OffsetDateTime.parse("2026-07-14T09:00:00Z"),
            UUID.randomUUID());

    assertThrows(
        IllegalArgumentException.class,
        () -> ledgerEventProcessor.process(metadata, "{\"status\":\"DECLINED\"}"));
    verifyNoInteractions(authorisationAuthorisedHandler, authorisationCapturedHandler);
  }
}
