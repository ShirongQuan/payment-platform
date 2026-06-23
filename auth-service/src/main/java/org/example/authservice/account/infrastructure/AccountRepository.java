package org.example.authservice.account.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AccountEntity}.
 *
 * <p>Provides standard CRUD operations (findById, save, delete, etc.) out of the box.
 * Add custom query methods here as needed (e.g. {@code findByCurrencyCode}).
 */
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {}
