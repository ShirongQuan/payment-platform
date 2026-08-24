package org.example.auth.outbox.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

  @Query(
"""
select e
from OutboxEventEntity e
where e.status = :status
  and e.nextAttemptAt <= :now
order by e.createdAt
""")
  List<OutboxEventEntity> findNextBatch(
      @Param("status") OutboxEventStatus status,
      @Param("now") OffsetDateTime now,
      Pageable pageable);

  @Modifying
  @Transactional
  @Query(
"""
update OutboxEventEntity e
set e.status = :newStatus,
    e.nextAttemptAt = :now,
    e.claimedAt = null,
    e.claimUntil = null
where e.status = :publishingStatus
  and (e.claimUntil is null or e.claimUntil < :now)
""")
  int reclaimStalePublishing(
      @Param("now") OffsetDateTime now,
      @Param("newStatus") OutboxEventStatus newStatus,
      @Param("publishingStatus") OutboxEventStatus publishingStatus);

  @Modifying
  @Transactional
  @Query(
"""
update OutboxEventEntity e
set e.status = :publishingStatus,
    e.claimedAt = :claimedAt,
    e.claimUntil = :claimUntil
where e.id = :id
  and e.status = :newStatus
""")
  int claimNewEvent(
      @Param("id") UUID id,
      @Param("claimedAt") OffsetDateTime claimedAt,
      @Param("claimUntil") OffsetDateTime claimUntil,
      @Param("publishingStatus") OutboxEventStatus publishingStatus,
      @Param("newStatus") OutboxEventStatus newStatus);

  @Modifying
  @Transactional
  @Query(
"""
Update OutboxEventEntity e
set e.status = :publishedStatus,
    e.publishedAt = :publishedAt,
    e.lastError = null,
    e.claimedAt = null,
    e.claimUntil = null
where e.id =:id
  and e.status = :publishingStatus
  and e.claimedAt = :claimedAt
""")
  int markPublished(
      @Param("id") UUID id,
      @Param("publishedAt") OffsetDateTime publishedAt,
      @Param("publishedStatus") OutboxEventStatus publishedStatus,
      @Param("publishingStatus") OutboxEventStatus publishingStatus,
      @Param("claimedAt") OffsetDateTime claimedAt);

  @Modifying
  @Transactional
  @Query(
"""
update OutboxEventEntity e
set e.retryCount = :retryCount,
  e.status = :newStatus,
  e.nextAttemptAt = :nextAttemptAt,
  e.lastError = :lastError,
  e.claimedAt = null,
  e.claimUntil = null
where e.id = :id
  and e.status = :publishingStatus
  and e.claimedAt = :claimedAt
""")
  int markRetry(
      @Param("id") UUID id,
      @Param("retryCount") int retryCount,
      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
      @Param("lastError") String lastError,
      @Param("newStatus") OutboxEventStatus newStatus,
      @Param("publishingStatus") OutboxEventStatus publishingStatus,
      @Param("claimedAt") OffsetDateTime claimedAt);

  @Modifying
  @Transactional
  @Query(
      """
    update OutboxEventEntity e
    set e.retryCount = :retryCount,
      e.status = :failedStatus,
      e.lastError = :lastError,
      e.claimedAt = null,
      e.claimUntil = null
    where e.id = :id
      and e.status = :publishingStatus
      and e.claimedAt = :claimedAt
    """)
  int markFailed(
      @Param("id") UUID id,
      @Param("retryCount") int retryCount,
      @Param("lastError") String lastError,
      @Param("failedStatus") OutboxEventStatus failedStatus,
      @Param("publishingStatus") OutboxEventStatus publishingStatus,
      @Param("claimedAt") OffsetDateTime claimedAt);
}
