# payment-platform
Fintech-inspired payment platform using Java, Spring Boot, PostgreSQL, Kafka, and Redis. Showcases real-time authorisation, balance reservation, concurrency control, outbox-based event publishing, ledger updates, circuit breaking, tracing, metrics, and load testing.

# simplified high level architecture
```mermaid
flowchart TD
    C[Client / Swagger UI / Postman / Load Test]

    subgraph AUTH[Authorization Service]
        A1[Authorize / Capture / Reverse Payments]
        A2[Idempotency + Balance Checks]
        A3[State Machine + Locking]
        A4[Fraud Check + Resilience]
        A5[Write Outbox Events]
    end

    subgraph DB1[Auth DB]
        D1[(PostgreSQL)]
    end

    subgraph REDIS[Redis]
        R1[Cache / Rate Limit / Idempotency Support]
    end

    subgraph FRAUD[Fraud Service]
        F1[Risk Decision API]
    end

    subgraph OUTBOX[Outbox Publisher]
        O1[Publish Events to Kafka]
    end

    subgraph KAFKA[Kafka]
        K1[Payment Events]
        K2[DLQ]
    end

    subgraph LEDGER[Ledger Service]
        L1[Immutable Ledger]
        L2[Balance Projection]
    end

    subgraph DB2[Ledger DB]
        D2[(PostgreSQL)]
    end

    subgraph OTHER[Other Consumers]
        N1[Notification Service]
        A6[Analytics Service]
        R2[Reconciliation Job / Service]
    end

    subgraph OBS[Observability]
        OB1[OpenTelemetry]
        OB2[Prometheus]
        OB3[Grafana]
    end

    subgraph SHARED[Shared Library]
        S1[Common Errors / Tracing / Security / Event Model]
    end

    C --> AUTH
    AUTH --> D1
    AUTH --> REDIS
    AUTH --> FRAUD
    AUTH --> OUTBOX
    OUTBOX --> KAFKA

    KAFKA --> LEDGER
    LEDGER --> D2

    KAFKA --> N1
    KAFKA --> A6
    D1 --> R2
    D2 --> R2

    AUTH -. uses .-> SHARED
    LEDGER -. uses .-> SHARED
    FRAUD -. uses .-> SHARED

    AUTH --> OB1
    LEDGER --> OB1
    FRAUD --> OB1
    OUTBOX --> OB1

    AUTH --> OB2
    LEDGER --> OB2
    OUTBOX --> OB2
    R2 --> OB2

    OB2 --> OB3

```


# Local server IP address

- auth service: 9000
- fraud service: 9100
- ledger service: 9200

- kafka ui: 9091
- kafka nodes: 9092, 9093, 9094

- postgres: 5434
- pgadmin: 5050

