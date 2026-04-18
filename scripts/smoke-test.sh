#!/usr/bin/env bash
# Safe smoke test after `docker compose up -d` (run from repo root or any cwd).
set -euo pipefail

BASE="${CLIENT_URL:-http://localhost:8080}"
REGION="${SMOKE_REGION:-ca-east}"
# Seed accounts: ca-east-00 (credited), ca-east-01 (debtor other)
ACCOUNT="${SMOKE_ACCOUNT_ID:-b7592f32-d833-52f5-83c4-1c2f367e52ab}"
DEBTOR_OTHER="${SMOKE_DEBTOR_OTHER:-0a99d33f-2eef-5aa8-a2e1-0eae24f67164}"

wait_health() {
  local end=$((SECONDS + 30))
  while (( SECONDS < end )); do
    if curl -fsS "${BASE}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "smoke-test: client health check failed for ${BASE}/actuator/health (30s timeout)" >&2
  exit 1
}

post_payload() {
  local idx="$1"
  cat <<EOF
{
  "messageId": "smoke-msg-${idx}",
  "creationDateTime": "2026-04-18T12:00:00Z",
  "numberOfTransactions": "1",
  "totalInterbankSettlementAmount": "10.50",
  "interbankSettlementCurrency": "CAD",
  "paymentInformationId": "smoke-pay-${idx}",
  "paymentMethod": "TRF",
  "instructionPriority": "NORM",
  "requestedExecutionDate": "2026-04-18",
  "debtor": null,
  "debtorAccount": {
    "iban": null,
    "other": "${DEBTOR_OTHER}",
    "currency": "CAD"
  },
  "debtorAgent": null,
  "creditor": null,
  "creditorAccount": {
    "iban": null,
    "other": "${ACCOUNT}",
    "currency": "CAD"
  },
  "creditorAgent": null,
  "creditTransferTransactionInformation": {
    "instructionId": "smoke-instr-${idx}",
    "endToEndId": "smoke-e2e-${idx}",
    "transactionId": "smoke-txn-${idx}",
    "paymentTypeInformation": "RTP",
    "instructedAmount": "10.50",
    "instructedCurrency": "CAD",
    "chargeBearer": "DEBT",
    "remittanceInformation": {
      "unstructured": "smoke test",
      "reference": "SMOKE"
    },
    "valueDate": "2026-04-18",
    "localInstrument": "RTP"
  }
}
EOF
}

echo "smoke-test: waiting for client at ${BASE} ..."
wait_health

for i in 1 2 3 4 5; do
  code="$(curl -s -o /tmp/smoke-post.json -w "%{http_code}" -X POST \
    "${BASE}/api/v1/ledger/${REGION}/${ACCOUNT}/post" \
    -H "Content-Type: application/json" \
    -d "$(post_payload "${i}")")"
  if [[ "${code}" != "202" ]]; then
    echo "smoke-test: POST ${i} expected HTTP 202, got ${code}. Body:" >&2
    cat /tmp/smoke-post.json >&2 || true
    exit 1
  fi
done

echo "smoke-test: five postings accepted (202); polling balance ..."

wait_balance_nonzero() {
  local end=$((SECONDS + 45))
  while (( SECONDS < end )); do
    if curl -fsS "${BASE}/api/v1/ledger/${REGION}/${ACCOUNT}/balance" -o /tmp/smoke-bal.json 2>/dev/null; then
      if python3 - "$ACCOUNT" <<'PY'
import json, sys
acct = sys.argv[1]
with open("/tmp/smoke-bal.json") as f:
    d = json.load(f)
bal = d.get("balance")
if bal is None:
    sys.exit(2)
# balance may be string or number in JSON
from decimal import Decimal
v = Decimal(str(bal))
sys.exit(0 if v != 0 else 3)
PY
      then
        return 0
      fi
    fi
    sleep 1
  done
  echo "smoke-test: balance did not become non-zero within 45s (server/NATS path may be down)" >&2
  exit 1
}

wait_balance_nonzero

echo "smoke-test: OK (balance non-zero)"
exit 0
