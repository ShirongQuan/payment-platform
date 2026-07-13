package org.example.ledger.application;

import java.time.OffsetDateTime;
import java.util.Map;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.example.ledger.infrastructure.AuthorisationAuthorisedPayload;
import org.example.ledger.infrastructure.LedgerEntryMapper;
import org.example.ledger.infrastructure.LedgerEntryRepository;
import org.example.ledger.infrastructure.LedgerEventLogRepository;
import org.example.ledger.infrastructure.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthorisationAuthorisedHandler {

  private final ProcessedEventRepository processedEventRepository;
  private final LedgerEventLogRepository ledgerEventLogRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final LedgerEntryMapper ledgerEntryMapper;
  private final ObjectMapper objectMapper;

  public AuthorisationAuthorisedHandler(
      ProcessedEventRepository processedEventRepository,
      LedgerEventLogRepository ledgerEventLogRepository,
      LedgerEntryRepository ledgerEntryRepository,
      LedgerEntryMapper ledgerEntryMapper,
      ObjectMapper objectMapper) {
    this.processedEventRepository = processedEventRepository;
    this.ledgerEventLogRepository = ledgerEventLogRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.ledgerEntryMapper = ledgerEntryMapper;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void handle(EventMetadata metadata, String rawPayload) {
    int inserted =
        processedEventRepository.tryInsertProcessedEvent(
            metadata.eventId(), EventType.AUTHORISATION_AUTHORISED.name(), OffsetDateTime.now());
    if (inserted == 0) {
      return;
    }

    Map<String, Object> payloadJson = objectMapper.readValue(rawPayload, new TypeReference<>() {});
    AuthorisationAuthorisedPayload payload =
        objectMapper.convertValue(payloadJson, AuthorisationAuthorisedPayload.class);

    ledgerEventLogRepository.save(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson));

    ledgerEntryRepository.save(ledgerEntryMapper.toLedgerEntry(metadata, payload, payloadJson));
  }
}
