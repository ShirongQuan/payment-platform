package org.example.auth.authorisation.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorisationRepository extends JpaRepository<AuthorisationEntity, UUID> {

  Optional<AuthorisationEntity> findByAccountIdAndIdempotencyKey(
      UUID accountId, String idempotencyKey);
}
