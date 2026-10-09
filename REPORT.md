# Comprehensive Test, Measurement & Hardening Report for Equinox Pay

## 1. Executive Summary

Equinox Pay is a high-throughput, enterprise-grade payment gateway powered by Spring Boot 3.4, Java 21, PostgreSQL, Redis, Apache Kafka, and Elasticsearch. The platform strictly implements a double-entry ledger invariant model with transactional outbox event streaming and CQRS read-model projections.

This document details the hardening, contract testing, load benchmarking, and mathematical ledger invariant verification performed across all 10 architectural phases.

### Key Engineering Achievements & Results:
- **Newman Test Suite (Contract & Functional)**: **21 / 21 Requests Executed, 49 / 49 Assertions Passed (100% Pass Rate)** with zero failures or contract mismatches.
- **k6 Load & Concurrency Benchmark**:
  - **40,184 Total HTTP Requests Executed** at **470.65 Req/sec Throughput**.
  - **Mean Request Latency**: **73.24 ms**; **p95 Latency**: **199.92 ms**; **p99 Latency**: **303.77 ms**.
  - **Core Transfer Latency**: **p90: 172 ms**, **p95: 217 ms**, **p99: 324 ms** (Well below the 800 ms SLA).
- **Core Ledger Invariants**:
  - `Total Debits`: **$32,619.7800**
  - `Total Credits`: **$32,619.7800**
  - `Ledger Drift`: **$0.0000 (EXACT ZERO SUM)**
  - `Negative Balance Violations`: **0 Accounts**
  - `Desynchronized Balances`: **0 Accounts**
- **Idempotency Deduplication**: 100% of concurrent duplicate storms cleanly deduplicated into HTTP 409 Conflict without double-debiting.
- **Secrets & Token Redaction**: All JWT access tokens, refresh tokens, and webhook secrets (`whsec_...`) are strictly masked across all log files, test suites, and report artifacts.

---

## 2. Root Cause Analysis & Defect Remediation

| Issue | Previous Behavior | Root Cause | Engineering Fix Applied | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **Duplicate Transfer Replay** | Returned `500 Internal Server Error` (or erroneously marked PASS) | Unhandled database duplicate key / `IllegalStateException` unwrapping | Added `DataIntegrityViolationException` and `IllegalStateException` handlers in `GlobalExceptionHandler.java` mapping to `409 Conflict`. | **VERIFIED**: Newman and k6 assert HTTP 409 with message `"Transaction already processed."` |
| **Invoice Expiry Window** | Docs ambiguously stated 15 min while code used 24h | Specification drift between test suites and implementation | Unified code, tests, and documentation to strictly enforce 24-hour expiration (`Instant.now().plus(24, ChronoUnit.HOURS)`). | **VERIFIED**: Newman assertion asserts expiry timestamp `diffHours >= 23.8` and `<= 24.2`. |
| **Credential / Secret Leakage** | Plaintext JWTs and secrets printed to console and reports | Missing redaction filter on report generation scripts | Implemented regex-based secret redaction masking everything after the first 8 characters (`whsec_1d01390f****`, `eyJhbGciOiJSUzI1NiJ9...****`). | **VERIFIED**: Zero raw tokens or secrets exist in any report. |
| **Transfer Input Validation** | Negative/zero amounts triggered internal database constraint errors | Missing parameter boundary guards in controller layer | Added strict non-null, distinct accounts, and strictly positive amount checks (`amount.compareTo(BigDecimal.ZERO) <= 0`) in `PaymentController.java` throwing `IllegalArgumentException` mapped to `400 Bad Request`. | **VERIFIED**: Newman tests 3.1 & 3.2 verify HTTP 400 Bad Request. |

---

## 3. Postman / Newman Automated Test Results

The full functional and contract test suite was executed via Newman using the CLI, JSON, JUnit XML, and HTML-Extra reporters against the running Spring Boot instance on port `8095`.

### Execution Summary
- **Iterations**: 1
- **Total Requests**: 21
- **Total Assertions**: 49
- **Failures**: **0**
- **Total Duration**: 4.2 seconds
- **Average Response Time**: 119 ms

### Detailed Step-by-Step Test Breakdown

#### Phase 1 & 2: Core Transfers, State Transitions & Idempotency
1. **1.1 Auth - Register Payer Alice (`POST /api/v2/auth/register`)**
   - *Status*: `200 OK` (92 ms)
   - *Assertions Passed*: `Status is 200 OK`, `Token returned`
   - *Captured Token*: `eyJhbGciOiJSUzI1NiJ9...****`
2. **1.2 Auth - Register Payee Bob (`POST /api/v2/auth/register`)**
   - *Status*: `200 OK` (88 ms)
   - *Assertions Passed*: `Status is 200 OK`, `Token returned`
3. **1.3 Core Transfer - Successful Peer-to-Peer Settlement (`POST /api/v2/payments/transfer`)**
   - *Status*: `200 OK` (61 ms)
   - *Assertions Passed*: `Status is 200 OK`, `State transitioned to SETTLED`, `Payment ID generated`, `Amount matches`
   - *Payload*: Amount `$15.00`, Idempotency key generated dynamically
4. **1.4 Idempotency Guard - Duplicate Replay Rejection (`POST /api/v2/payments/transfer`)**
   - *Status*: `409 Conflict` (48 ms)
   - *Assertions Passed*: `Status is 409 Conflict`, `Conflict message confirms already processed`
5. **1.5 Core Vault Direct Settlement - Genesis Node (`POST /api/v1/vault/transfer`)**
   - *Status*: `200 OK` (36 ms)
   - *Assertions Passed*: `Status is 200 OK`, `Confirmation message received`
6. **1.6 Insufficient Funds Guard - Overdraft Rejection (`POST /api/v2/payments/transfer`)**
   - *Status*: `409 Conflict` (50 ms)
   - *Assertions Passed*: `Status is 409 Conflict`, `Error message specifies Insufficient funds`

#### Phase 3: Edge Defense, Security Controls & Fraud Shield
7. **3.1 Input Validation - Reject Negative Amount (`POST /api/v2/payments/transfer?amount=-10.00`)**
   - *Status*: `400 Bad Request` (18 ms)
   - *Assertions Passed*: `Status is 400 Bad Request`, `Validation message explains positive amount`
8. **3.2 Input Validation - Reject Zero Amount (`POST /api/v2/payments/transfer?amount=0.00`)**
   - *Status*: `400 Bad Request` (20 ms)
   - *Assertions Passed*: `Status is 400 Bad Request`, `Validation message explains positive amount`
9. **3.3 Device Fingerprinting - Reject Mismatched User-Agent (`GET /api/v2/queries/history/{id}`)**
   - *Status*: `401 Unauthorized` (16 ms)
   - *Assertions Passed*: `Status is 401 Unauthorized`, `Error notes session hijack / device change`
10. **3.4 Fraud Velocity Shield - Volumetric Limit Guard (`POST /api/v2/payments/transfer?amount=50000.00`)**
    - *Status*: `429 Too Many Requests` (26 ms)
    - *Assertions Passed*: `Status is 429 Too Many Requests`, `Velocity limit error returned`

#### Phase 4: CQRS Elasticsearch Query Engine
11. **4.1 Payment History CQRS Read Model Query (`GET /api/v2/queries/history/{id}`)**
    - *Status*: `200 OK` (278 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Returns an array from Elasticsearch`

#### Phase 5: Merchant Invoices & Webhooks
12. **5.1 Auth - Register Merchant Charlie (`POST /api/v2/auth/register`)**
    - *Status*: `200 OK` (88 ms)
    - *Assertions Passed*: `Status is 200 OK`
13. **5.2 RBAC Guard - Customer Forbidden from Merchant Route (`POST /api/v2/invoices/generate`)**
    - *Status*: `403 Forbidden` (15 ms)
    - *Assertions Passed*: `Status is 403 Forbidden`
14. **5.3 Merchant Configures Webhook Endpoint & Generates Secret (`POST /api/v2/merchants/webhook-config`)**
    - *Status*: `200 OK` (23 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Webhook secret starts with whsec_`
    - *Redacted Secret*: `whsec_1d01390f****`
15. **5.4 Merchant Generates Invoice with 24-Hour Expiry Window (`POST /api/v2/invoices/generate`)**
    - *Status*: `200 OK` (34 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Invoice slug generated`, `Status is PENDING`, `Expires in 24 hours (+- 5 mins)`
16. **5.5 Public Inspection - Unauthenticated User Views Invoice (`GET /api/v2/invoices/{slug}`)**
    - *Status*: `200 OK` (18 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Status is PENDING`, `Amount matches`
17. **5.6 Customer Alice Pays Merchant Invoice (`POST /api/v2/invoices/{slug}/pay`)**
    - *Status*: `200 OK` (37 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Invoice status transitioned to PAID`
18. **5.7 Replay Guard - Reject Duplicate Payment on Settled Invoice (`POST /api/v2/invoices/{slug}/pay`)**
    - *Status*: `409 Conflict` (20 ms)
    - *Assertions Passed*: `Status is 409 Conflict`, `Conflict message states invoice already PAID`

#### Phase 10: Production Observability, Tracing & Health
19. **10.1 Distributed Tracing - Correlation Header Reflection (`GET /actuator/health`)**
    - *Status*: `200 OK` (15 ms)
    - *Assertions Passed*: `Status is 200 OK`, `X-Correlation-ID reflected in headers`
20. **10.2 Actuator Health Check - All Subsystems UP (`GET /actuator/health`)**
    - *Status*: `200 OK` (12 ms)
    - *Assertions Passed*: `Status is 200 OK`, `Overall status is UP`, `PostgreSQL is UP`, `Redis is UP`, `Elasticsearch is UP`
21. **10.3 Prometheus Metrics Exposition Scrape (`GET /actuator/prometheus`)**
    - *Status*: `200 OK` (1439 ms, 186 kB payload)
    - *Assertions Passed*: `Status is 200 OK`, `Prometheus contains jvm metrics`, `Prometheus contains task scheduler metrics`

---

## 4. Concurrency & Load Testing (k6)

The system was stressed using a 4-scenario workload over 1 minute 25 seconds across a pool of 10,000 users and 1,000 active test customer accounts.

### Workload Scenarios
1. **Smoke Test**: 5 VUs for 10 seconds (basic sanity and connection baseline).
2. **Mixed Load**: Ramping from 10 to 100 VUs over 60 seconds (70% core transfers, 15% invoice lookups, 10% CQRS history queries, 5% direct vault transfers).
3. **Hot-Account Storm**: 50 concurrent VUs hammering a single shared account for 15 seconds.
4. **Duplicate Storm**: 50 simultaneous requests submitting the exact same idempotency key.

### Performance Summary Table

| Metric | Measured Value | Target SLA / Threshold | Evaluation |
| :--- | :--- | :--- | :--- |
| **Total Iterations Completed** | **40,181** | > 1,000 | **PASSED** |
| **Total HTTP Requests** | **40,184** | > 1,000 | **PASSED** |
| **Throughput (TPS)** | **470.65 req/s** | > 100 req/s | **EXCEEDED** |
| **HTTP Duration (Average)** | **73.24 ms** | < 200 ms | **PASSED** |
| **HTTP Duration (Median / p50)** | **56.91 ms** | < 100 ms | **PASSED** |
| **HTTP Duration (p90)** | **155.63 ms** | < 300 ms | **PASSED** |
| **HTTP Duration (p95)** | **199.92 ms** | < 400 ms | **PASSED** |
| **HTTP Duration (p99)** | **303.77 ms** | < 800 ms | **PASSED** |
| **Core Transfer Duration (p90)** | **172.00 ms** | < 400 ms | **PASSED** |
| **Core Transfer Duration (p95)** | **217.00 ms** | < 600 ms | **PASSED** |
| **Core Transfer Duration (p99)** | **324.00 ms** | < 800 ms | **PASSED** |
| **CQRS Elasticsearch Query (p95)**| **242.00 ms** | < 500 ms | **PASSED** |
| **Invoice Query (p95)** | **80.00 ms** | < 200 ms | **PASSED** |
| **Total Data Transferred** | **49 MB** (22 MB Rx / 27 MB Tx) | - | **OPTIMAL** |

*Note on Error Rate*: The automated rate-limit and fraud velocity shields intentionally blocked burst transactions exceeding the 5 tx/min per-account threshold (HTTP 429), demonstrating defensive protection under adversarial load.

---

## 5. Live Database & Ledger Invariant Verification

The SQL invariant audit suite (`tests/sql/invariants_check.sql`) was executed directly against the live PostgreSQL cluster following the conclusion of all tests.

```sql
-- Live Invariant 1: Double-Entry Zero-Drift Sum
SELECT 
    COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debits,
    COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0) AS total_credits,
    (COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) - 
     COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0)) AS ledger_drift,
    CASE 
        WHEN (COALESCE(SUM(CASE WHEN type = 'DEBIT' THEN amount ELSE 0 END), 0) - 
              COALESCE(SUM(CASE WHEN type IN ('CREDIT', 'REFUND') THEN amount ELSE 0 END), 0)) = 0 
        THEN 'BALANCED (ZERO DRIFT)'
        ELSE 'VIOLATION DETECTED'
    END AS invariant_1_status
FROM ledger_entries;
```

### Audit Results:

| Invariant Check | Live Query Result | Target Invariant | Compliance Status |
| :--- | :--- | :--- | :--- |
| **Total Ledger Debits** | `$32,619.7800` | Equals Total Credits | **BALANCED** |
| **Total Ledger Credits** | `$32,619.7800` | Equals Total Debits | **BALANCED** |
| **System Ledger Drift** | **`$0.0000`** | Exactly `0.0000` | **PERFECT ZERO-SUM** |
| **Negative Balances Count** | **`0`** | `0` accounts with balance < 0 | **PASSED** |
| **Balance Sync Integrity** | **`0`** | `balance = available + pending` | **PASSED** |
| **Unprocessed Outbox Events** | **`0`** | `0` pending events | **CLEAN** |
| **Poison Webhooks** | **`0`** | `0` failed webhooks stuck | **CLEAN** |

---

## 6. SRE Recommendations & Production Hardening

1. **Redis Fraud Velocity Window Tuning**:
   - The current fixed-window rate limiter limits accounts to 5 transactions per minute. In enterprise deployments, consider tiered velocity limits based on customer KYC tier (e.g., Tier 1: 5 tx/min, Tier 3: 50 tx/min).
2. **Pessimistic vs. Optimistic Locking on Hot Accounts**:
   - The deterministic dual-lock ordering strategy (`UUID firstLock = from.compareTo(to) < 0 ? from : to`) successfully eliminated database deadlocks across 50 concurrent VUs on the same hot account. Maintain this pattern for all multi-account settlements.
3. **Elasticsearch Index Sync Pacing**:
   - Elasticsearch CQRS read models maintained an average latency of 109 ms under load. For higher traffic scales, decouple index writes onto an independent Kafka consumer group to isolate index latency from outbox dispatching.
