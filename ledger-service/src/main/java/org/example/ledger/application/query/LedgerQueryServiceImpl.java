package org.example.ledger.application.query;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.ledger.api.AccountEvent;
import org.example.ledger.api.AccountEventsResponse;
import org.example.ledger.api.AuthenticationResponse;
import org.example.ledger.infrastructure.persistence.LedgerEntryEntity;
import org.example.ledger.infrastructure.persistence.LedgerEntryMapper;
import org.example.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.springframework.stereotype.Service;

/** Query-side service reading ledger projection tables for API responses. */
@Slf4j
@Service
public class LedgerQueryServiceImpl implements LedgerQueryService {

  private final LedgerEntryRepository ledgerEntryRepository;
  private final LedgerEntryMapper ledgerEntryMapper;

  public LedgerQueryServiceImpl(
      LedgerEntryRepository ledgerEntryRepository, LedgerEntryMapper ledgerEntryMapper) {
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.ledgerEntryMapper = ledgerEntryMapper;
  }

  @Override
  public List<AuthenticationResponse> getAuthorisationsById(UUID authorisationId) {
    log.debug("Querying ledger entries by authorisationId={}", authorisationId);

    List<LedgerEntryEntity> ledgerEntryEntity =
        ledgerEntryRepository.findAuthorisationsById(authorisationId);

    log.debug(
        "Found ledger entries for authorisation query, authorisationId={}, count={}",
        authorisationId,
        ledgerEntryEntity.size());

    return ledgerEntryEntity.stream().map(ledgerEntryMapper::toAuthorisationResponse).toList();
  }

  @Override
  public AccountEventsResponse getAccountEvents(UUID accountId) {
    log.debug("Querying ledger account events, accountId={}", accountId);

    List<AccountEvent> accountEvents =
        ledgerEntryRepository.findAccountEventsById(accountId).stream()
            .map(
                e ->
                    new AccountEvent(
                        e.getEventId(),
                        e.getEventType(),
                        e.getAggregateId(),
                        e.getOccurredAt(),
                        e.getAmount(),
                        e.getCurrencyCode()))
            .toList();
    log.debug("Built account events response, accountId={}, count={}", accountId, accountEvents.size());
    return new AccountEventsResponse(accountId, accountEvents);
  }
}
