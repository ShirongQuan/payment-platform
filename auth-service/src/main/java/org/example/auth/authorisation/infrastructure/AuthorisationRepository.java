package org.example.auth.authorisation.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorisationRepository extends JpaRepository<AuthorisationEntity, UUID> {

  @Query(
      value =
          """
          select a.*
          from authorisation a
          join authorisation_event e on e.authorisation_id = a.authorisation_id
          where e.account_id = :accountId
            and e.idempotency_key = :idempotencyKey
          order by e.created_at desc
          limit 1
          """,
      nativeQuery = true)
  Optional<AuthorisationEntity> findByAccountIdAndIdempotencyKey(
      @Param("accountId") UUID accountId, @Param("idempotencyKey") String idempotencyKey);
}
