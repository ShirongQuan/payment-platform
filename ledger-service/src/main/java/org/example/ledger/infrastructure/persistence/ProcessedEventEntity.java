package org.example.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.ledger.domain.EventType;

@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProcessedEventEntity {

  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "event_type", nullable = false, length = 50)
  @Enumerated(EnumType.STRING)
  private EventType eventType;

  @Column(name = "processed_at", nullable = false)
  private OffsetDateTime processedAt;
}
