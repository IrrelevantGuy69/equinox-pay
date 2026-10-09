# ADR-001: Deadlock-Free Pessimistic Locking Strategy via Deterministic UUID Sorting

## Status
Accepted

## Context
In a multi-party B2B financial ledger, concurrent balance transfers between shared accounts create circular wait conditions if locks are acquired in arbitrary sequence. For example:
- Thread A transfers from Account 1 to Account 2 (locks Account 1, awaits Account 2).
- Thread B transfers from Account 2 to Account 1 (locks Account 2, awaits Account 1).
Under high transaction throughput (e.g., thousands of simultaneous payouts or clearing sweeps), database deadlocks occur frequently, causing transaction rollbacks, latency spikes, and degraded user experience.

## Decision
We enforce a global, deterministic locking order across all multi-party operations:
1. **Dynamic ID Sorting**: Prior to acquiring any write locks, all participating account IDs (`UUID`) are sorted lexicographically using `UUID.compareTo()`.
2. **Sequential Lock Acquisition**: The database rows are locked in this deterministic sorted order using `@Lock(LockModeType.PESSIMISTIC_WRITE)` with a strict lock timeout (`jakarta.persistence.lock.timeout = 5000ms`).
3. **Multi-Party Generalization**: In multi-leg split payments and batch settlements involving $N$ parties (payer, platform, merchant, affiliates), all $N$ IDs are collected into a sorted unique list before locking.

```java
List<UUID> sortedIds = Stream.of(payerId, payeeId)
        .sorted()
        .toList();
```

## Consequences
### Positive
- **Deadlock Freedom**: Eliminates circular lock dependencies at the database level by ensuring lock hierarchy.
- **Predictable Latency**: Prevents PostgreSQL deadlock detector invocations and aborts.
- **High Concurrency**: Accounts experience clean queued serialization rather than deadlock aborts.

### Negative / Trade-offs
- Requires pre-determining all account IDs before entering the atomic write block.
- Read operations on accounts must either be uncommitted or read from CQRS query replicas to avoid blocking on write locks.
