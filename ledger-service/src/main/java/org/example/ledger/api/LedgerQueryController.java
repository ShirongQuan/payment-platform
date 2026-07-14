package org.example.ledger.api;

import java.util.List;
import java.util.UUID;
import org.example.ledger.application.query.LedgerQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LedgerQueryController {

  private final LedgerQueryService ledgerQueryService;

  public LedgerQueryController(LedgerQueryService ledgerQueryService) {
    this.ledgerQueryService = ledgerQueryService;
  }

  @GetMapping("/authorisations/{authorisationId}")
  public List<AuthenticationResponse> getAuthorisations(@PathVariable UUID authorisationId) {
    return ledgerQueryService.getAuthorisationsById(authorisationId);
  }

  @GetMapping("/accounts/{accountId}/events")
  public AccountEventsResponse getAccountEvents(@PathVariable UUID accountId) {
    return ledgerQueryService.getAccountEvents(accountId);
  }
}
