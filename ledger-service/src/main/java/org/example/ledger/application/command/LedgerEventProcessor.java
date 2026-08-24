package org.example.ledger.application.command;

import lombok.extern.slf4j.Slf4j;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.springframework.stereotype.Service;

/** Routes inbound authorisation events to the correct ledger command handler. */
@Slf4j
@Service
public class LedgerEventProcessor {
  private final AuthorisationAuthorisedHandler authorisationAuthorisedHandler;
  private final AuthorisationCapturedHandler authorisationCapturedHandler;
  private final AuthorisationReversedHandler authorisationReversedHandler;

  public LedgerEventProcessor(
      AuthorisationAuthorisedHandler authorisationAuthorisedHandler,
      AuthorisationCapturedHandler authorisationCapturedHandler,
      AuthorisationReversedHandler authorisationReversedHandler) {
    this.authorisationAuthorisedHandler = authorisationAuthorisedHandler;
    this.authorisationCapturedHandler = authorisationCapturedHandler;
    this.authorisationReversedHandler = authorisationReversedHandler;
  }

  public void process(EventMetadata metadata, String rawPayload) {
    final EventType eventType = EventType.valueOf(metadata.eventType());
    log.debug(
        "Routing event to handler, eventId={}, eventType={}, aggregateId={}",
        metadata.eventId(),
        eventType,
        metadata.aggregateId());
    // Dispatch to the handler matching the event type. AUTHORISATION_DECLINED events carry no
    // monetary effect on the ledger and are intentionally no-ops; any other/unknown event type
    // is treated as a configuration/contract error and fails loudly so it can be investigated
    // (and, depending on the Kafka error handler, routed to the dead-letter topic).
    switch (eventType) {
      case AUTHORISATION_AUTHORISED -> {
        authorisationAuthorisedHandler.handle(metadata, rawPayload);
        log.debug("Handled AUTHORISATION_AUTHORISED event, eventId={}", metadata.eventId());
      }
      case AUTHORISATION_CAPTURED -> {
        authorisationCapturedHandler.handle(metadata, rawPayload);
        log.debug("Handled AUTHORISATION_CAPTURED event, eventId={}", metadata.eventId());
      }
      case AUTHORISATION_REVERSED -> {
        authorisationReversedHandler.handle(metadata, rawPayload);
        log.debug("Handled AUTHORISATION_REVERSED event, eventId={}", metadata.eventId());
      }
      case AUTHORISATION_DECLINED -> {
        log.debug(
            "Ignoring AUTHORISATION_DECLINED event, eventId={}, no action taken",
            metadata.eventId());
      }
      default -> {
        log.error(
            "Unsupported event type received, eventId={}, eventType={}",
            metadata.eventId(),
            eventType);
        throw new IllegalArgumentException("Unsupported event type: " + metadata.eventType());
      }
    }
  }
}
