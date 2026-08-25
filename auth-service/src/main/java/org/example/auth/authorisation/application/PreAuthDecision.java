package org.example.auth.authorisation.application;

import org.example.auth.account.domain.AccountStatus;
import org.example.auth.fraud.FraudDecision;

/**
 * Outcome of the pre-authorisation checks performed by {@link AuthorisationServiceImpl} before
 * entering the transactional executor.
 *
 * <p>The account-active check happens first and gates whether fraud-service is called at all: if
 * the account isn't {@link AccountStatus#ACTIVE}, we short-circuit to {@link AccountNotActive}
 * without paying for an external fraud-service call. Otherwise, {@link FraudEvaluated} carries the
 * fraud decision (including any account-lock recommendation) obtained from fraud-service.
 */
public sealed interface PreAuthDecision {

  /** Account exists but is not {@link AccountStatus#ACTIVE}; fraud-service was not called. */
  record AccountNotActive(AccountStatus status) implements PreAuthDecision {}

  /** Account was ACTIVE at pre-check time; carries the fraud-service decision. */
  record FraudEvaluated(FraudDecision fraudDecision) implements PreAuthDecision {}
}

