package org.example.auth.outbox.application;

import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;

public interface OutboxEventService {
  void enqueueAuthorisation(
      Authorisation authorisation, AuthorisationEventEntity authorisationEventEntity);

  void publishNextBatch(int batchSize);
}
