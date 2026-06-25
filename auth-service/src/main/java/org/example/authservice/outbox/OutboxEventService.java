package org.example.authservice.outbox;

import org.example.authservice.authorisation.domain.Authorisation;

public interface OutboxEventService {
  void enqueueAuthorisation(Authorisation authorisation);
}
