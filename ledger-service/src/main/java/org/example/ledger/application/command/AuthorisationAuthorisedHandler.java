package org.example.ledger.application.command;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.ledger.domain.AuthorisationAuthorisedPayload;
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
/** Applies AUTHORISATION_AUTHORISED events into ledger projections with deduplication. */
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
    log.debug("Handling AUTHORISATION_AUTHORISED event, eventId={}", metadata.eventId());
    int inserted =
        processedEventRepository.tryInsertProcessedEvent(
            metadata.eventId(), EventType.AUTHORISATION_AUTHORISED.name(), OffsetDateTime.now());
    if (inserted == 0) {
      log.debug("Event with id {} already exist, do nothing", metadata.eventId());
      return;
    }
    log.debug("Event marked as first-time processing, eventId={}", metadata.eventId());

    Map<String, Object> payloadJson;
    AuthorisationAuthorisedPayload payload;
    try {
      payloadJson = objectMapper.readValue(rawPayload, new TypeReference<>() {});
      payload = objectMapper.convertValue(payloadJson, AuthorisationAuthorisedPayload.class);
    } catch (RuntimeException e) {
      log.error("Failed to parse AUTHORISATION_AUTHORISED payload, eventId={}", metadata.eventId(), e);
      throw new IllegalArgumentException(
          "Invalid AUTHORISATION_AUTHORISED payload for eventId=" + metadata.eventId(), e);
    }

    ledgerEventLogRepository.save(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson));
    log.debug("Saved ledger event log row, eventId={}", metadata.eventId());

    ledgerEntryRepository.save(ledgerEntryMapper.toLedgerEntry(metadata, payload, payloadJson));
    log.debug("Saved ledger entry row for authorised event, eventId={}", metadata.eventId());
  }
}
