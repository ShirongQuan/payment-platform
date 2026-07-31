package org.example.auth.outbox.application;

import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.common.OperationType;

/** Contract for writing business events to outbox and publishing pending batches. */
public interface OutboxEventService {
  void enqueueAuthorisation(
      AuthorisationEntity authorisationEntity,
      AuthorisationEventEntity authorisationEventEntity,
      OperationType operationType);

  void publishNextBatch(int batchSize);
}
