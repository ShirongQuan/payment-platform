package org.example.ledger.application.query;

import java.util.List;
import java.util.UUID;
import org.example.ledger.api.AccountEventsResponse;
import org.example.ledger.api.AuthenticationResponse;

/** Read-side contract for querying ledger projections by authorisation and account. */
public interface LedgerQueryService {
  List<AuthenticationResponse> getAuthorisationsById(UUID authorisationId);

  AccountEventsResponse getAccountEvents(UUID accountId);
}
