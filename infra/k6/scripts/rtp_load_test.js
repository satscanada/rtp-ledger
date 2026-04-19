import http from "k6/http";
import { check } from "k6";
import { buildLedgerPostPayload, randomAmount } from "./payload_generator.js";
import { HOT_ACCOUNT_ID, DEFAULT_DEBTOR_ID, SEED_ACCOUNTS, randomAccountPair } from "./accounts.js";

const BASE_URL = __ENV.BASE_URL || "http://rtp-client:8080";

function postUrl(region, creditorId) {
  return `${BASE_URL}/api/v1/ledger/${region}/${creditorId}/post`;
}

/** Debtors for hot-account scenario: any seeded account except HOT (index 0). */
function debtorForHotBurst(vu) {
  return SEED_ACCOUNTS[2 + (vu % 98)].accountId;
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    warm_up: {
      executor: "constant-vus",
      vus: 10,
      duration: "30s",
      exec: "warmUp",
    },
    hot_account_burst: {
      executor: "constant-vus",
      vus: 200,
      duration: "60s",
      startTime: "30s",
      exec: "hotBurst",
    },
    mixed_load: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s", target: 400 },
        { duration: "60s", target: 800 },
        { duration: "30s", target: 0 },
      ],
      startTime: "30s",
      exec: "mixedLoad",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.001"],
    "http_req_duration{scenario:warm_up}": ["p(99)<8"],
    "http_req_duration{scenario:hot_account_burst}": ["p(95)<15", "p(99)<30"],
    "http_req_duration{scenario:mixed_load}": ["p(95)<12", "p(99)<25"],
  },
};

export function warmUp() {
  const region = "ca-east";
  const creditor = HOT_ACCOUNT_ID;
  const debtor = DEFAULT_DEBTOR_ID;
  const body = buildLedgerPostPayload(debtor, creditor, randomAmount(1.0, 99.99), "wu");
  const res = http.post(postUrl(region, creditor), body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "202 accepted": (r) => r.status === 202 });
}

export function hotBurst() {
  const region = "ca-east";
  const creditor = HOT_ACCOUNT_ID;
  const debtor = debtorForHotBurst(__VU);
  const body = buildLedgerPostPayload(debtor, creditor, randomAmount(1.0, 99.99), "hot");
  const res = http.post(postUrl(region, creditor), body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "202 accepted": (r) => r.status === 202 });
}

export function mixedLoad() {
  const { creditor, debtor } = randomAccountPair(Math.random);
  const body = buildLedgerPostPayload(
    debtor.accountId,
    creditor.accountId,
    randomAmount(0.01, 99.99),
    "mix",
  );
  const res = http.post(postUrl(creditor.region, creditor.accountId), body, {
    headers: { "Content-Type": "application/json" },
  });
  check(res, { "202 accepted": (r) => r.status === 202 });
}
