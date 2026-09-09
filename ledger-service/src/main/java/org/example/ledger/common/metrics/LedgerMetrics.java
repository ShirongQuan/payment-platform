package org.example.ledger.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Custom Kafka consumer / dead-letter-topic (DLT) metrics for ledger-service, exported via
 * Actuator's /actuator/prometheus endpoint.
 *
 * <p>Callers (the Kafka listener and error-handler configuration) depend only on this class, not
 * on {@link MeterRegistry} directly, keeping metric names/tags centralized in one place.
 */
@Component
public class LedgerMetrics {

  // Kafka consumer metrics
  public static final String KAFKA_MESSAGES_RECEIVED_TOTAL =
      "ledger_kafka_consumer_messages_received_total";

  // DLT metric — top-priority panel: any non-zero rate means records are being permanently
  // dropped from normal processing and need investigation.
  public static final String KAFKA_DLT_PUBLISHED_TOTAL = "ledger_kafka_dlt_published_total";

  public static final String TOPIC_TAG = "topic";
  public static final String DLT_TOPIC_TAG = "dltTopic";
  public static final String EXCEPTION_TAG = "exceptionClass";

  private final MeterRegistry meterRegistry;

  // Tag values (topic / dltTopic / exceptionClass) are finite/low-cardinality, but consumer
  // throughput is high, so counters are built/registered once per distinct combination and cached
  // here rather than re-built via Counter.builder(...).register(...) on every record.
  private final Map<String, Counter> receivedCounters = new ConcurrentHashMap<>();
  private final Map<DltPublishedKey, Counter> dltPublishedCounters = new ConcurrentHashMap<>();

  public LedgerMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * Increments the count of Kafka records received by the ledger consumer, per source topic. Used
   * alongside container-reported lag (kafka_consumer_fetch_manager_records_lag_max) to build the
   * "consumer lag vs. throughput" panel.
   */
  public void incrementReceived(String topic) {
    receivedCounters
        .computeIfAbsent(
            topic,
            t ->
                Counter.builder(KAFKA_MESSAGES_RECEIVED_TOTAL)
                    .description("Total number of Kafka records received by the ledger consumer")
                    .tag(TOPIC_TAG, t)
                    .register(meterRegistry))
        .increment();
  }

  /**
   * Increments the count of records published to the dead-letter topic, tagged with the
   * originating topic, destination DLT topic, and the exception that caused recovery. Drives the
   * "DLT publish rate by exception type" panel/alert.
   */
  public void incrementDltPublished(String sourceTopic, String dltTopic, String exceptionClass) {
    dltPublishedCounters
        .computeIfAbsent(
            new DltPublishedKey(sourceTopic, dltTopic, exceptionClass),
            key ->
                Counter.builder(KAFKA_DLT_PUBLISHED_TOTAL)
                    .description("Total number of Kafka records published to the dead-letter topic")
                    .tag(TOPIC_TAG, key.sourceTopic())
                    .tag(DLT_TOPIC_TAG, key.dltTopic())
                    .tag(EXCEPTION_TAG, key.exceptionClass())
                    .register(meterRegistry))
        .increment();
  }

  /** Cache key for {@link #dltPublishedCounters}; all three tag values are bounded. */
  private record DltPublishedKey(String sourceTopic, String dltTopic, String exceptionClass) {}
}

