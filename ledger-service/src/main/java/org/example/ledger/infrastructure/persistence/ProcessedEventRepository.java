package org.example.ledger.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stores processed event ids to guarantee idempotent consumer behavior.
 *
 * <p>{@link #tryInsertProcessedEvent(UUID, String, OffsetDateTime)} returns {@code 1} only for the
 * first successful insert and {@code 0} when the event id already exists.
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

  @Modifying
  @Query(
      value =
          """
          insert into processed_event (event_id, event_type, processed_at)
          values (:eventId, :eventType, :processedAt)
          on conflict (event_id) do nothing
          """,
      nativeQuery = true)
  int tryInsertProcessedEvent(
      @Param("eventId") UUID eventId,
      @Param("eventType") String eventType,
      @Param("processedAt") OffsetDateTime processedAt);
}
