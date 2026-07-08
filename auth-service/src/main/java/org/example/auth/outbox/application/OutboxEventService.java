package org.example.auth.outbox.application;

import org.example.auth.authorisation.domain.Authorisation;

public interface OutboxEventService {
  void enqueueAuthorisation(Authorisation authorisation);

  void publishNextBatch(int batchSize);
}
