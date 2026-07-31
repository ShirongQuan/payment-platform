package org.example.ledger.api;

import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.example.ledger.application.query.LedgerQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read-only API endpoints exposing ledger projections for authorisations and account timelines. */
@Slf4j
@RestController
public class LedgerQueryController {

  private final LedgerQueryService ledgerQueryService;

  public LedgerQueryController(LedgerQueryService ledgerQueryService) {
    this.ledgerQueryService = ledgerQueryService;
  }

  @GetMapping("/authorisations/{authorisationId}")
  public List<AuthenticationResponse> getAuthorisations(@PathVariable UUID authorisationId) {
    log.debug("Received ledger query by authorisationId={}", authorisationId);
    return ledgerQueryService.getAuthorisationsById(authorisationId);
  }

  @GetMapping("/accounts/{accountId}/events")
  public AccountEventsResponse getAccountEvents(@PathVariable UUID accountId) {
    log.debug("Received ledger account events query, accountId={}", accountId);
    return ledgerQueryService.getAccountEvents(accountId);
  }
}
