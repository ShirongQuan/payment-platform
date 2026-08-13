package org.example.fraud.application;

import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.api.FraudCheckResult;

public interface FraudService {
  FraudCheckResult check(FraudCheckRequest request);
}
