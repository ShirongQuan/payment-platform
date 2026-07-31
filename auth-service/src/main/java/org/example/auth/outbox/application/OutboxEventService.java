package org.example.auth.outbox.application;

import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.common.OperationType;

public interface OutboxEventService {
  void enqueueAuthorisation(
      AuthorisationEntity authorisationEntity,
      AuthorisationEventEntity authorisationEventEntity,
      OperationType operationType);

  void publishNextBatch(int batchSize);
}
