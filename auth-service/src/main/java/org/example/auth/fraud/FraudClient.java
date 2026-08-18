package org.example.auth.fraud;

public interface FraudClient {

  FraudDecision check(FraudCheckRequest request);
}
