import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Load accounts pool
const accountsData = JSON.parse(open('./accounts_pool.json'));
const customerPool = accountsData.customer_account_ids || [];
const merchantPool = accountsData.merchant_account_ids || [];

// Custom Metrics
export const errorRate = new Rate('equinox_error_rate');
export const transferLatency = new Trend('equinox_transfer_duration_ms');
export const invoiceQueryLatency = new Trend('equinox_invoice_query_duration_ms');
export const cqrsQueryLatency = new Trend('equinox_cqrs_query_duration_ms');
export const completedTransfers = new Counter('equinox_completed_transfers');
export const duplicateBlockedCounter = new Counter('equinox_duplicate_blocked');

export const options = {
  scenarios: {
    // 1. Smoke test (verifies connectivity & basic latency)
    smoke: {
      executor: 'constant-vus',
      vus: 5,
      duration: '10s',
      startTime: '0s',
    },
    // 2. Mixed load test (70% transfers, 15% invoices, 10% history queries, 5% vault direct)
    mixed_load: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '15s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '15s', target: 0 },
      ],
      startTime: '10s',
    },
    // 3. Concurrency storm against 1 hot account (100 VUs)
    hot_account_storm: {
      executor: 'constant-vus',
      vus: 50,
      duration: '15s',
      startTime: '70s',
    },
    // 4. Duplicate idempotency storm (50 simultaneous requests with exact same key)
    duplicate_storm: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 50,
      maxDuration: '10s',
      startTime: '85s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<400', 'p(99)<800'],
    equinox_error_rate: ['rate<0.05'],
    equinox_transfer_duration_ms: ['p(99)<800'],
  },
};

const BASE_URL = __ENV.EQUINOX_BASE_URL || 'http://localhost:8095';
const SHARED_DUPLICATE_KEY = 'fixed-duplicate-storm-key-' + Date.now();

// Setup phase: Authenticate a loadtest customer to get a valid JWT and create sample invoice
export function setup() {
  const regRes = http.post(`${BASE_URL}/api/v2/auth/register`, JSON.stringify({
    username: `k6_runner_${Date.now()}`,
    password: 'Password123!',
    role: 'USER',
  }), {
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'k6-load-runner/1.0',
    },
  });

  let token = '';
  if (regRes.status === 200) {
    token = JSON.parse(regRes.body).token;
  }

  // Register merchant and generate an invoice for load testing queries
  const merchRes = http.post(`${BASE_URL}/api/v2/auth/register`, JSON.stringify({
    username: `k6_merch_${Date.now()}`,
    password: 'Password123!',
    role: 'MERCHANT',
  }), {
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'k6-load-runner/1.0',
    },
  });

  let invoiceSlug = 'inv_test_default';
  if (merchRes.status === 200) {
    const merchToken = JSON.parse(merchRes.body).token;
    const invRes = http.post(`${BASE_URL}/api/v2/invoices/generate`, JSON.stringify({
      amount: '50.00'
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${merchToken}`,
        'User-Agent': 'k6-load-runner/1.0',
      }
    });
    if (invRes.status === 200) {
      invoiceSlug = JSON.parse(invRes.body).slug;
    }
  }

  return { token: token, invoiceSlug: invoiceSlug };
}

export default function (data) {
  const headers = {
    'Authorization': `Bearer ${data.token}`,
    'User-Agent': 'k6-load-runner/1.0',
    'X-Correlation-ID': `k6-trace-${__VU}-${__ITER}`,
  };

  const poolLength = customerPool.length;
  // Pick from the 1000 accounts pool based on VU and iteration to spread evenly
  const fromIdx = (__VU * 17 + __ITER) % poolLength;
  const toIdx = (fromIdx + 37) % poolLength;
  const fromAcc = customerPool[fromIdx];
  const toAcc = customerPool[toIdx];

  // If duplicate storm scenario is running:
  if (__ENV.SCENARIO === 'duplicate_storm' || (__VU >= 40 && __ITER < 2)) {
    const res = http.post(
      `${BASE_URL}/api/v2/payments/transfer?from=${customerPool[0]}&to=${customerPool[1]}&amount=1.00`,
      null,
      {
        headers: Object.assign({}, headers, { 'Idempotency-Key': SHARED_DUPLICATE_KEY }),
      }
    );
    if (res.status === 200) {
      completedTransfers.add(1);
      errorRate.add(0);
    } else if (res.status === 409) {
      duplicateBlockedCounter.add(1);
      errorRate.add(0); // Expected idempotency deduplication
    } else {
      errorRate.add(1);
    }
    return;
  }

  // Traffic Distribution:
  // 70% Core transfers
  // 15% Invoice queries
  // 10% CQRS Elasticsearch history queries
  // 5% Direct Vault transfers
  const rand = Math.random();

  if (rand < 0.70) {
    const idemp = `mix-${__VU}-${__ITER}-${Date.now()}`;
    const amount = (Math.random() * 4 + 0.1).toFixed(2);
    const start = Date.now();
    const res = http.post(
      `${BASE_URL}/api/v2/payments/transfer?from=${fromAcc}&to=${toAcc}&amount=${amount}`,
      null,
      {
        headers: Object.assign({}, headers, { 'Idempotency-Key': idemp }),
      }
    );
    transferLatency.add(Date.now() - start);

    if (res.status === 200 || res.status === 409) {
      completedTransfers.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  } else if (rand < 0.85) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v2/invoices/${data.invoiceSlug}`);
    invoiceQueryLatency.add(Date.now() - start);
    errorRate.add(res.status === 200 ? 0 : 1);
  } else if (rand < 0.95) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v2/queries/history/${fromAcc}`, { headers: headers });
    cqrsQueryLatency.add(Date.now() - start);
    errorRate.add(res.status === 200 ? 0 : 1);
  } else {
    const idemp = `vault-${__VU}-${__ITER}-${Date.now()}`;
    const res = http.post(
      `${BASE_URL}/api/v1/vault/transfer?from=${fromAcc}&to=${toAcc}&amount=1.00`,
      null,
      {
        headers: Object.assign({}, headers, { 'Idempotency-Key': idemp }),
      }
    );
    errorRate.add(res.status === 200 ? 0 : 1);
  }

  sleep(0.02); // 20ms pacing
}

export function teardown(data) {
  console.log('=== K6 LOAD EXECUTION COMPLETE ===');
}
