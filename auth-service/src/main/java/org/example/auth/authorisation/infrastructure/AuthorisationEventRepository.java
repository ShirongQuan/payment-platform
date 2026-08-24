package org.example.auth.authorisation.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for the append-only {@link AuthorisationEventEntity} audit trail. */
@Repository
public interface AuthorisationEventRepository
    extends JpaRepository<AuthorisationEventEntity, UUID> {

  /**
   * Looks up a specific lifecycle event by its natural idempotency key, used to detect and
   * replay duplicate capture/reverse requests once an authorisation has already reached the
   * corresponding terminal status.
   */
  @Query(
      value =
          """
        select e.*
        from authorisation_event e
        where e.account_id = :accountId
          and e.idempotency_key = :idempotencyKey
          and e.event_type = :eventType
        limit 1
        """,
      nativeQuery = true)
  Optional<AuthorisationEventEntity> findByAccountIdAndEventTypeAndIdempotencyKey(
      @Param("accountId") UUID accountId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("eventType") String eventType);
}
