package org.example.ledger.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Query repository for ledger projection reads.
 *
 * <p>Provides timeline views per authorisation aggregate and account-level event feeds sorted by
 * occurrence time.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

  @Query(
"""
select e
from LedgerEntryEntity e
where e.aggregateType = 'AUTHORISATION'
  AND e.aggregateId = :authorisationId
order by e.occurredAt desc
""")
  List<LedgerEntryEntity> findAuthorisationsById(@Param("authorisationId") UUID authorisationId);

  @Query(
"""
select
  e.eventId as eventId,
  e.eventType as eventType,
  e.aggregateId as aggregateId,
  e.occurredAt as occurredAt,
  e.amount as amount,
  e.currencyCode as currencyCode
from LedgerEntryEntity e
where e.accountId = :accountId
order by e.occurredAt desc
""")
  List<AccountEventView> findAccountEventsById(@Param("accountId") UUID accountId);
}
