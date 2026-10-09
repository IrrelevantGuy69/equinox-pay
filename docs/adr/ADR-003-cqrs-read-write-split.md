# ADR-003: CQRS Read/Write Architecture Split

## Status
Accepted

## Context
High-volume financial ledgers experience vastly divergent access patterns:
1. **Write Path (OLTP)**: Strictly normalized, zero-loss ACID transactions requiring row-level pessimistic locks and immediate double-entry validation.
2. **Read Path (OLAP / Analytics / Auditing)**: High-frequency queries by merchants, customers, and operations dashboards seeking fast filtering, searching across statements, pagination, and multi-field sorting.

Executing complex full-text and analytical queries against the write-heavy PostgreSQL ledger tables risks lock contention, database CPU saturation, and unacceptably high P99 write latencies.

## Decision
We enforce a **Command Query Responsibility Segregation (CQRS)** architecture:
1. **Command / Write Model**:
   - Backed by PostgreSQL (`accounts`, `ledger_entries`, `payments`).
   - Focused strictly on atomic ledger state mutations, lock hierarchy, and outbox emission.
2. **Query / Read Model**:
   - Backed by Elasticsearch (`PaymentDocument`) and Redis read caches.
   - Decoupled from the write model via asynchronous Kafka consumer streams (`CqrsSyncConsumer`).
   - The Kafka consumer ingests payment state change events and projects denormalized read-optimized documents into Elasticsearch.
3. **Auditing & Reconciliation**:
   - Fast merchant statement searches query Elasticsearch.
   - Formal daily ledger audits run batch queries against PostgreSQL read replicas with zero impact on primary OLTP transactions.

```
[Write Requests] ---> [Command Handler] ---> [PostgreSQL (Primary ACID)]
                                                      |
                                                  (Outbox)
                                                      |
                                                      v
                                               [Kafka Topic]
                                                      |
                                             (CqrsSyncConsumer)
                                                      |
                                                      v
[Search Requests] <--- [Query Handler]   <--- [Elasticsearch (Read Engine)]
```

## Consequences
### Positive
- **Independent Scaling**: Read search capacity scales horizontally with Elasticsearch shards without impacting database locks.
- **Sub-50ms Dashboard Search**: Complex multi-attribute merchant filters execute in milliseconds without SQL joins.
- **Write Path Isolation**: PostgreSQL write connections remain focused purely on ACID balance safety.

### Negative / Trade-offs
- **Eventual Consistency**: Read replicas and Elasticsearch reflect updates with sub-second event propagation lag.
- **Operational Complexity**: Requires operating and monitoring Elasticsearch sync pipelines and consumer offsets.
