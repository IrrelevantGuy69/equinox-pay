# ADR-002: Dual-Write Mitigation via Transactional Outbox Pattern

## Status
Accepted

## Context
When processing payment mutations (e.g. transfers, auth holds, merchant settlements), the application must persist state changes to PostgreSQL and simultaneously emit domain events to Apache Kafka for CQRS indexing and asynchronous webhook delivery.

Directly publishing to Kafka inside the database transaction boundary creates the **Dual-Write Problem**:
- If the Kafka publish fails after the DB commit, asynchronous subscribers miss critical financial events.
- If Kafka succeeds but the DB transaction rolls back, downstream systems process phantom transactions.
- Using 2-Phase Commit (XA transactions) between PostgreSQL and Kafka introduces high latency, poor throughput, and single points of failure.

## Decision
We implement the **Transactional Outbox Pattern**:
1. Domain state and corresponding events (`OutboxEvent`) are committed atomically within the same local PostgreSQL ACID transaction.
2. An asynchronous sweeper worker (`OutboxRelayService`) polls pending events using ShedLock-guaranteed leader execution.
3. Events are published to Kafka with message idempotency (`enable.idempotence=true`), acknowledgments (`acks=all`), and correlation tracing headers (`X-Trace-Id`).
4. Upon successful receipt acknowledgement from Kafka, the outbox record is marked `PROCESSED`.
5. Poison-pill messages that exhaust backoff retries are routed to Dead Letter Queues (`.DLT`) and increment the `equinox.kafka.dlq.count` metric.

```
[Client] -> [PostgreSQL Transaction]
              |-- Save Payment & Ledger Entries
              |-- Insert OutboxEvent (PENDING)
            [Commit]
                |
          (Async Poller: OutboxRelayService)
                |
                v
       [Publish to Apache Kafka]
                |
                v
       [Mark OutboxEvent PROCESSED]
```

## Consequences
### Positive
- **Guaranteed At-Least-Once Delivery**: Events cannot be lost due to network partition or broker restarts.
- **Zero Inconsistency**: Financial ledger mutations and events are committed atomically.
- **Decoupled Throughput**: High-frequency writes are not blocked by Kafka broker latencies.

### Negative / Trade-offs
- Downstream consumers must implement idempotency checks to handle potential at-least-once duplicate replays.
- Adds slight polling latency for event propagation (configurable via sweeper intervals).
