package org.example.ledger.application.query;

import java.util.List;
import java.util.UUID;
import org.example.ledger.api.AccountEvent;
import org.example.ledger.api.AccountEventsResponse;
import org.example.ledger.api.AuthenticationResponse;
import org.example.ledger.infrastructure.persistence.LedgerEntryEntity;
import org.example.ledger.infrastructure.persistence.LedgerEntryMapper;
import org.example.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.springframework.stereotype.Service;

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

    List<LedgerEntryEntity> ledgerEntryEntity =
        ledgerEntryRepository.findAuthorisationsById(authorisationId);

    return ledgerEntryEntity.stream().map(ledgerEntryMapper::toAuthorisationResponse).toList();
  }

  @Override
  public AccountEventsResponse getAccountEvents(UUID accountId) {

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
    return new AccountEventsResponse(accountId, accountEvents);
  }
}
