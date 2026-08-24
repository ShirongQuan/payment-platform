package org.example.auth.authorisation.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for the current-state {@link AuthorisationEntity} rows. */
@Repository
public interface AuthorisationRepository extends JpaRepository<AuthorisationEntity, UUID> {

  /**
   * Finds the most recent authorisation created for an {@code (accountId, idempotencyKey)} pair,
   * used to detect and replay duplicate authorise requests. Only considers the initial
   * authorise-lifecycle events (AUTHORISED/DECLINED), not later capture/reverse events.
   */
  @Query(
      value =
          """
          select a.*
          from authorisation a
          join authorisation_event e on e.authorisation_id = a.authorisation_id
          where e.account_id = :accountId
            and e.idempotency_key = :idempotencyKey
             and e.event_type in ('AUTHORISATION_AUTHORISED', 'AUTHORISATION_DECLINED')
          order by e.created_at desc
          limit 1
          """,
      nativeQuery = true)
  Optional<AuthorisationEntity> findByAccountIdAndAuthoriseEventTypesAndIdempotencyKey(
      @Param("accountId") UUID accountId, @Param("idempotencyKey") String idempotencyKey);
}
