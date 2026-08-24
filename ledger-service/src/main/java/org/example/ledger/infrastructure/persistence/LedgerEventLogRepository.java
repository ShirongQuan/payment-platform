package org.example.ledger.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CRUD repository for the raw, append-only {@link LedgerEventLogEntity} audit log. */
public interface LedgerEventLogRepository extends JpaRepository<LedgerEventLogEntity, UUID> {}
