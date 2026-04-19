import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Counter } from "k6/metrics";
import { buildLedgerPostPayload, randomAmount } from "./payload_generator.js";
import { HOT_ACCOUNT_ID, SEED_ACCOUNTS, randomAccountPair } from "./accounts.js";

const BASE_URL = __ENV.BASE_URL || "http://rtp-client:8080";
const phase = __ENV.CONCURRENT_SCENARIO || "balance";

const rate503 = new Rate("rtp_503_rate");
const count500 = new Counter("rtp_500_total");

function postUrl(region, creditorId) {
  return `${BASE_URL}/api/v1/ledger/${region}/${creditorId}/post`;
}

function balanceUrl(region, accountId) {
  return `${BASE_URL}/api/v1/ledger/${region}/${accountId}/balance`;
}

function parseBalance(body) {
  try {
    const j = JSON.parse(body);
    return Number(j.balance);
  } catch {
    return NaN;
  }
}

function optionsForPhase(p) {
  if (p === "balance") {
    return {
      scenarios: {
        balance_correctness: {
          executor: "shared-iterations",
          vus: 500,
          iterations: 50000,
          maxDuration: "20m",
          exec: "balancePost",
        },
      },
      thresholds: {
        http_req_failed: ["rate<0.02"],
        "http_req_duration{scenario:balance_correctness}": ["p(99)<50"],
      },
      setupTimeout: "120s",
      teardownTimeout: "120s",
    };
  }
  if (p === "burst") {
    return {
      scenarios: {
        burst_spike: {
          executor: "ramping-vus",
          startVUs: 0,
          stages: [
            { duration: "10s", target: 2000 },
            { duration: "10s", target: 2000 },
            { duration: "5s", target: 0 },
          ],
          exec: "burstPost",
        },
      },
      thresholds: {
        rtp_503_rate: ["rate<0.05"],
        rtp_500_total: ["count<=0"],
      },
    };
  }
  if (p === "parallel") {
    const scenarios = {};
    for (let i = 0; i < 10; i++) {
      scenarios[`lane_${i}`] = {
        executor: "constant-vus",
        vus: 50,
        duration: "60s",
        exec: "lanePost",
        env: { LANE: String(i) },
        tags: { lane: String(i) },
      };
    }
    const thresholds = {
      http_req_failed: ["rate<0.02"],
    };
    for (let i = 0; i < 10; i++) {
      thresholds[`http_req_duration{lane:${i}}`] = ["p(99)<40"];
    }
    return { scenarios, thresholds };
  }
  throw new Error(`Unknown CONCURRENT_SCENARIO="${p}" (use balance | burst | parallel)`);
}

export const options = optionsForPhase(phase);

export function setup() {
  if (phase !== "balance") {
    return {};
  }
  const res = http.get(balanceUrl("ca-east", HOT_ACCOUNT_ID));
  const initial = parseBalance(res.body);
  if (Number.isNaN(initial)) {
    throw new Error(`Could not read initial balance: HTTP ${res.status} body=${res.body}`);
  }
  return { initial };
}

export function teardown(data) {
  if (phase !== "balance" || data.initial === undefined) {
    return;
  }
  let res = { status: 0, body: "" };
  for (let i = 0; i < 45; i++) {
    res = http.get(balanceUrl("ca-east", HOT_ACCOUNT_ID));
    if (res.status === 200) {
      const b = parseBalance(res.body);
      if (!Number.isNaN(b)) {
        const expected = data.initial + 50000.0;
        const ok = Math.abs(b - expected) < 0.05;
        if (!ok) {
          throw new Error(`Balance check failed: initial=${data.initial} final=${b} expected≈${expected}`);
        }
        return;
      }
    }
    sleep(1);
  }
  throw new Error(`Balance check failed: could not GET balance after run (last status ${res.status})`);
}

export function balancePost() {
  const region = "ca-east";
  const creditor = HOT_ACCOUNT_ID;
  const idx = 2 + ((__VU + __ITER) % 98);
  const debtor = SEED_ACCOUNTS[idx].accountId;
  const body = buildLedgerPostPayload(debtor, creditor, "1.00", "bc");
  const res = http.post(postUrl(region, creditor), body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "202 accepted": (r) => r.status === 202 });
}

export function burstPost() {
  const { creditor, debtor } = randomAccountPair(Math.random);
  const body = buildLedgerPostPayload(
    debtor.accountId,
    creditor.accountId,
    randomAmount(1.0, 50.0),
    "bst",
  );
  const res = http.post(postUrl(creditor.region, creditor.accountId), body, {
    headers: { "Content-Type": "application/json" },
  });
  rate503.add(res.status === 503 ? 1 : 0);
  if (res.status === 500) {
    count500.add(1);
  }
}

export function lanePost() {
  const laneIndex = Number(__ENV.LANE || "0");
  const creditor = SEED_ACCOUNTS[laneIndex];
  const debtor = SEED_ACCOUNTS[(laneIndex + 50) % 100];
  const body = buildLedgerPostPayload(
    debtor.accountId,
    creditor.accountId,
    "5.00",
    `ln${laneIndex}`,
  );
  const res = http.post(postUrl(creditor.region, creditor.accountId), body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "202 accepted": (r) => r.status === 202 });
}
