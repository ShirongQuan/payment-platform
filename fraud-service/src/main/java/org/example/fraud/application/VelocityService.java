package org.example.fraud.application;

import java.time.Duration;

public interface VelocityService {
  boolean isTooFrequent(String dimension, String name, int maxRequests, Duration window);
}
