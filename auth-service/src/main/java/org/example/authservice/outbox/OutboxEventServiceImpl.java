package org.example.authservice.outbox;

import java.time.ZonedDateTime;
import java.util.UUID;
import org.example.authservice.authorisation.domain.Authorisation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxEventServiceImpl implements OutboxEventService {
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public OutboxEventServiceImpl(
      OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  // Enforce being called inside a transaction.
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void enqueueAuthorisation(Authorisation authorisation) {
    AuthorisationCreatedPayload payload =
        new AuthorisationCreatedPayload(
            authorisation.getId(),
            authorisation.getAccountId(),
            authorisation.getAmount(),
            authorisation.getCurrencyCode(),
            authorisation.getStatus(),
            authorisation.getFailureReason(),
            authorisation.getCreatedAt());

    OutboxEventEntity outboxEvent = new OutboxEventEntity();
    outboxEvent.setId(UUID.randomUUID());
    outboxEvent.setAggregateType("AUTHORISATION");
    outboxEvent.setAggregateId(authorisation.getId());

    EventType eventType =
        switch (authorisation.getStatus()) {
          case DECLINED -> EventType.AUTHORISATION_DECLINED;
          case AUTHORISED -> EventType.AUTHORISATION_AUTHORISED;
          case CAPTURED -> EventType.AUTHORISATION_CAPTURED;
          case REVERSED -> EventType.AUTHORISATION_REVERSED;
          default ->
              throw new IllegalStateException(
                  "Unsupported authorisation status for outbox event: "
                      + authorisation.getStatus());
        };
    outboxEvent.setEventType(eventType);
    outboxEvent.setPayload(objectMapper.valueToTree(payload));
    outboxEvent.setStatus(OutboxEventStatus.PENDING);
    outboxEvent.setRetryCount(0);
    outboxEvent.setLastError(null);
    outboxEvent.setCreatedAt(ZonedDateTime.now());
    outboxEvent.setIdempotencyKey(authorisation.getIdempotencyKey());
    outboxEvent.setCorrelationId(UUID.randomUUID()); // TODO
    outboxEventRepository.save(outboxEvent);
  }
}
