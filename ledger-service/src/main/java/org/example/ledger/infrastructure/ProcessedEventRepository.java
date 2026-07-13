package org.example.ledger.infrastructure;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

  @Modifying
  @Query(
      value =
          """
          insert into processed_events (event_id, event_type, processed_at)
          values (:eventId, :eventType, :processedAt)
          on conflict (event_id) do nothing
          """,
      nativeQuery = true)
  int tryInsertProcessedEvent(
      @Param("eventId") UUID eventId,
      @Param("eventType") String eventType,
      @Param("processedAt") OffsetDateTime processedAt);
}
