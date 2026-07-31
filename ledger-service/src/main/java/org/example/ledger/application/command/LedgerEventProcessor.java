package org.example.ledger.application.command;

import lombok.extern.slf4j.Slf4j;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LedgerEventProcessor {
  private final AuthorisationAuthorisedHandler authorisationAuthorisedHandler;
  private final AuthorisationCapturedHandler authorisationCapturedHandler;

  public LedgerEventProcessor(
      AuthorisationAuthorisedHandler authorisationAuthorisedHandler,
      AuthorisationCapturedHandler authorisationCapturedHandler) {
    this.authorisationAuthorisedHandler = authorisationAuthorisedHandler;
    this.authorisationCapturedHandler = authorisationCapturedHandler;
  }

  public void process(EventMetadata metadata, String rawPayload) {
    final EventType eventType = EventType.valueOf(metadata.eventType());
    log.debug("Handling event, event type: {}", eventType);
    switch (eventType) {
      case AUTHORISATION_AUTHORISED -> {
        authorisationAuthorisedHandler.handle(metadata, rawPayload);
      }
      case AUTHORISATION_CAPTURED -> {
        authorisationCapturedHandler.handle(metadata, rawPayload);
      }
      default -> {
        throw new IllegalArgumentException("Unsupported event type: " + metadata.eventType());
      }
    }
  }
}
