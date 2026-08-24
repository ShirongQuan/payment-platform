package org.example.fraud.application;

import java.time.Duration;

/**
 * Sliding-window frequency counter used by velocity-based risk rules (e.g. {@link
 * org.example.fraud.component.AccountRule}, {@link org.example.fraud.component.IpRule}).
 */
public interface VelocityService {

  /**
   * Records a new event for the given dimension/name pair and returns whether more than {@code
   * maxRequests} events have occurred within the trailing {@code window}.
   *
   * @param dimension logical bucket, e.g. "account" or "ip"
   * @param name the specific key within that dimension, e.g. an accountId or IP address
   * @param maxRequests threshold above which the caller should consider this "too frequent"
   * @param window sliding time window to count events over
   * @return {@code true} if the event count within the window exceeds {@code maxRequests}
   */
  boolean isTooFrequent(String dimension, String name, int maxRequests, Duration window);
}
