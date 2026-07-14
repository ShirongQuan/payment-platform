package org.example.ledger.application.command;

import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.springframework.stereotype.Service;

@Service
public class LedgerEventProcessor {
  private final AuthorisationAuthorisedHandler authorisationAuthorisedHandler;

  public LedgerEventProcessor(AuthorisationAuthorisedHandler authorisationAuthorisedHandler) {
    this.authorisationAuthorisedHandler = authorisationAuthorisedHandler;
  }

  public void process(EventMetadata metadata, String rawPayload) throws Exception {

    final EventType eventType = EventType.valueOf(metadata.eventType());
    switch (eventType) {
      case AUTHORISATION_AUTHORISED -> {
        authorisationAuthorisedHandler.handle(metadata, rawPayload);
      }
      default -> {
        throw new IllegalArgumentException("Unsupported event type: " + metadata.eventType());
      }
    }
  }
}
