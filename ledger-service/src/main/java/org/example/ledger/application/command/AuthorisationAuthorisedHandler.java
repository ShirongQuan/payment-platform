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

/** Applies AUTHORISATION_AUTHORISED events into ledger projections with deduplication. */
@Slf4j
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

  /**
   * Applies a single AUTHORISATION_AUTHORISED event to the ledger, exactly once.
   *
   * <p>Steps: (1) atomically claim the event id to guarantee idempotency, short-circuiting if it
   * was already processed; (2) parse and validate the raw JSON payload; (3) append the raw event
   * to the audit log; (4) append a new ledger entry projection row reflecting the authorisation.
   * All steps run in a single transaction so a failure rolls back the idempotency claim too.
   */
  @Transactional
  public void handle(EventMetadata metadata, String rawPayload) {
    log.debug("Handling AUTHORISATION_AUTHORISED event, eventId={}", metadata.eventId());

    // Step 1: claim this event id via unique insert; inserted == 0 means we've already
    // processed it before (duplicate delivery), so we skip further work.
    int inserted =
        processedEventRepository.tryInsertProcessedEvent(
            metadata.eventId(), EventType.AUTHORISATION_AUTHORISED.name(), OffsetDateTime.now());
    if (inserted == 0) {
      log.debug("Event with id {} already exist, do nothing", metadata.eventId());
      return;
    }
    log.debug("Event marked as first-time processing, eventId={}", metadata.eventId());

    // Step 2: parse the raw JSON both as a generic map (kept for audit logging) and as a
    // strongly-typed payload (used to build the ledger entry projection).
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

    // Step 3: persist the raw event for audit/traceability purposes.
    ledgerEventLogRepository.save(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson));
    log.debug("Saved ledger event log row, eventId={}", metadata.eventId());

    // Step 4: append the business-level ledger entry projection row.
    ledgerEntryRepository.save(ledgerEntryMapper.toLedgerEntry(metadata, payload, payloadJson));
    log.debug("Saved ledger entry row for authorised event, eventId={}", metadata.eventId());
  }
}
