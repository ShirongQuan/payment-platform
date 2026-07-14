package org.example.ledger.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventLogRepository extends JpaRepository<LedgerEventLogEntity, UUID> {}
