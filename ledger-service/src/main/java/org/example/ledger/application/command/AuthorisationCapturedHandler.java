package org.example.ledger.application.command;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.ledger.domain.AuthorisationCapturedPayload;
import org.example.ledger.domain.EventMetadata;
import org.example.ledger.domain.EventType;
import org.example.ledger.infrastructure.persistence.LedgerEntryMapper;
import org.example.ledger.infrastructure.persistence.LedgerEntryRepository;
import org.example.ledger.infrastructure.persistence.LedgerEventLogRepository;
import org.example.ledger.infrastructure.persistence.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class AuthorisationCapturedHandler {

  private final ProcessedEventRepository processedEventRepository;
  private final LedgerEventLogRepository ledgerEventLogRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final LedgerEntryMapper ledgerEntryMapper;
  private final ObjectMapper objectMapper;

  public AuthorisationCapturedHandler(
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
            metadata.eventId(), EventType.AUTHORISATION_CAPTURED.name(), OffsetDateTime.now());
    if (inserted == 0) {
      log.debug("Event with id {} already exist, do nothing", metadata.eventId());
      return;
    }
    Map<String, Object> payloadJson = objectMapper.readValue(rawPayload, new TypeReference<>() {});
    AuthorisationCapturedPayload payload =
        objectMapper.convertValue(payloadJson, AuthorisationCapturedPayload.class);

    ledgerEventLogRepository.save(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson));

    ledgerEntryRepository.save(ledgerEntryMapper.toLedgerEntry(metadata, payload, payloadJson));
  }
}
