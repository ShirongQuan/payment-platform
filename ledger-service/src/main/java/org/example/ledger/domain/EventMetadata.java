package org.example.ledger.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EventMetadata(
    UUID eventId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    OffsetDateTime occurredAt,
    UUID correlationId) {}
