package org.example.ledger.application.command;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.ledger.common.metrics.LedgerMetrics;
import org.example.ledger.domain.AuthorisationReversedPayload;
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

/** Applies AUTHORISATION_REVERSED events into ledger projections with deduplication. */
@Slf4j
@Service
public class AuthorisationReversedHandler {

  private final ProcessedEventRepository processedEventRepository;
  private final LedgerEventLogRepository ledgerEventLogRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final LedgerEntryMapper ledgerEntryMapper;
  private final ObjectMapper objectMapper;
  private final LedgerMetrics ledgerMetrics;

  public AuthorisationReversedHandler(
      ProcessedEventRepository processedEventRepository,
      LedgerEventLogRepository ledgerEventLogRepository,
      LedgerEntryRepository ledgerEntryRepository,
      LedgerEntryMapper ledgerEntryMapper,
      ObjectMapper objectMapper,
      LedgerMetrics ledgerMetrics) {
    this.processedEventRepository = processedEventRepository;
    this.ledgerEventLogRepository = ledgerEventLogRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.ledgerEntryMapper = ledgerEntryMapper;
    this.objectMapper = objectMapper;
    this.ledgerMetrics = ledgerMetrics;
  }

  /**
   * Applies a single AUTHORISATION_REVERSED event to the ledger, exactly once.
   *
   * <p>Steps: (1) atomically claim the event id to guarantee idempotency, short-circuiting if it
   * was already processed; (2) parse and validate the raw JSON payload; (3) append the raw event
   * to the audit log; (4) append a new ledger entry projection row reflecting the reversal.
   */
  @Transactional
  public void handle(EventMetadata metadata, String rawPayload) {
    log.debug("Handling AUTHORISATION_REVERSED event, eventId={}", metadata.eventId());

    // Step 1: claim this event id via unique insert; inserted == 0 means duplicate delivery.
    int inserted =
        processedEventRepository.tryInsertProcessedEvent(
            metadata.eventId(), EventType.AUTHORISATION_REVERSED.name(), OffsetDateTime.now());
    if (inserted == 0) {
      log.debug("Event with id {} already exist, do nothing", metadata.eventId());
      ledgerMetrics.incrementDuplicateSkipped(EventType.AUTHORISATION_REVERSED.name());
      return;
    }
    log.debug("Event marked as first-time processing, eventId={}", metadata.eventId());

    // Step 2: parse the raw JSON as both a generic map (audit log) and typed payload (projection).
    Map<String, Object> payloadJson;
    AuthorisationReversedPayload payload;
    try {
      payloadJson = objectMapper.readValue(rawPayload, new TypeReference<>() {});
      payload = objectMapper.convertValue(payloadJson, AuthorisationReversedPayload.class);
    } catch (RuntimeException e) {
      log.error("Failed to parse AUTHORISATION_REVERSED payload, eventId={}", metadata.eventId(), e);
      throw new IllegalArgumentException(
          "Invalid AUTHORISATION_REVERSED payload for eventId=" + metadata.eventId(), e);
    }

    // Step 3: persist the raw event for audit/traceability purposes.
    ledgerEventLogRepository.save(ledgerEntryMapper.toEventLogEntity(metadata, payloadJson));
    log.debug("Saved ledger event log row, eventId={}", metadata.eventId());

    // Step 4: append the business-level ledger entry projection row.
    ledgerEntryRepository.save(ledgerEntryMapper.toReversedLedgerEntry(metadata, payload, payloadJson));
    log.debug("Saved ledger entry row for reversed event, eventId={}", metadata.eventId());
  }
}
