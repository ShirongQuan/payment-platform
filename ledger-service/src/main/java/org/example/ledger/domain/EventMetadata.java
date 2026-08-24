package org.example.ledger.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Envelope metadata extracted from Kafka record headers for an inbound authorisation event.
 *
 * @param eventId unique id of the event, used for idempotency/deduplication
 * @param aggregateType type of the source aggregate (e.g. "AUTHORISATION")
 * @param aggregateId id of the source aggregate the event belongs to
 * @param eventType name of the {@link EventType} this event represents
 * @param occurredAt time the event occurred at the source (auth-service)
 * @param correlationId id used to correlate this event across services for tracing/logging
 */
public record EventMetadata(
    UUID eventId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    OffsetDateTime occurredAt,
    UUID correlationId) {}
