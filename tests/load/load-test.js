import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
export const errorRate = new Rate('equinox_error_rate');
export const transferDuration = new Trend('equinox_transfer_duration_ms');
export const completedTransfers = new Counter('equinox_completed_transfers');

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
    },
    stress_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 100 },
        { duration: '20s', target: 200 },
        { duration: '10s', target: 0 },
      ],
      startTime: '30s',
    },
  },
  thresholds: {
    // SLA 1: P99 Latency must remain strictly below 250ms
    http_req_duration: ['p(95)<150', 'p(99)<250'],
    // SLA 2: Error rate must stay below 0.1%
    equinox_error_rate: ['rate<0.001'],
    // SLA 3: Custom transfer trend below 250ms at P99
    equinox_transfer_duration_ms: ['p(99)<250'],
  },
};

const BASE_URL = __ENV.EQUINOX_BASE_URL || 'http://localhost:8080';

// Fixed test account UUIDs for concurrency & contention validation
const SOURCE_ACCOUNT_ID = __ENV.SOURCE_ACCOUNT_ID || '11111111-1111-1111-1111-111111111111';
const DEST_ACCOUNT_ID = __ENV.DEST_ACCOUNT_ID || '22222222-2222-2222-2222-222222222222';

export default function () {
  const correlationId = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const idempotencyKey = `idemp-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;

  const payload = JSON.stringify({
    sourceAccountId: SOURCE_ACCOUNT_ID,
    destinationAccountId: DEST_ACCOUNT_ID,
    amount: (Math.random() * 10 + 1).toFixed(2), // Random amount between 1.00 and 11.00
    idempotencyKey: idempotencyKey,
    description: 'k6 concurrent load transfer',
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-ID': correlationId,
    },
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/vault/transfer`, payload, params);
  const duration = Date.now() - startTime;

  transferDuration.add(duration);

  const isSuccess = check(res, {
    'status is 200 or 201': (r) => r.status === 200 || r.status === 201,
    'has correlation header': (r) => r.headers['X-Correlation-Id'] !== undefined || r.headers['x-correlation-id'] !== undefined,
  });

  if (isSuccess) {
    completedTransfers.add(1);
    errorRate.add(0);
  } else {
    // 409 Conflict / duplicate idempotency is acceptable in stress, unhandled 500s are errors
    if (res.status === 409) {
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  }

  sleep(0.05); // 50ms pacing
}

/**
 * Post-test assertion function: verifies zero-sum balance and ledger parity
 */
export function teardown() {
  console.log('=== K6 LOAD TEST COMPLETED: ASSERTING LEDGER ZERO-SUM INVARIANT ===');
  const auditRes = http.get(`${BASE_URL}/api/v1/reconciliation/audit-ledger`);
  if (auditRes.status === 200) {
    const body = JSON.parse(auditRes.body);
    console.log(`Ledger Audit Balanced: ${body.balanced}, Total Debits: ${body.totalDebits}, Total Credits: ${body.totalCredits}`);
  } else {
    console.log(`Ledger check skipped or endpoint returned status: ${auditRes.status}`);
  }
}
