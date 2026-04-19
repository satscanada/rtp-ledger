/**
 * BIAN-inspired JSON payloads matching `BianCreditTransferTransaction` / smoke-test shape.
 * Amounts are strings with exactly 2 decimal places (never raw floats).
 */

/** @returns {string} eg "1234.56" */
export function randomAmount(min, max) {
  const v = min + Math.random() * (max - min);
  return v.toFixed(2);
}

/**
 * Full ledger POST body for /api/v1/ledger/{region}/{creditorAccountId}/post
 * Creditor receives funds (CRDT semantics via creditorAccount in smoke-style payload).
 *
 * @param {string} debtorAccountId UUID
 * @param {string} creditorAccountId UUID
 * @param {string} instructedAmountStr two-decimal amount string
 */
export function buildLedgerPostPayload(debtorAccountId, creditorAccountId, instructedAmountStr, tag) {
  const day = new Date().toISOString().slice(0, 10);
  const suffix = `${tag}-${__VU}-${__ITER}-${Date.now()}`;
  return JSON.stringify({
    messageId: `k6-msg-${suffix}`,
    creationDateTime: new Date().toISOString(),
    numberOfTransactions: "1",
    totalInterbankSettlementAmount: instructedAmountStr,
    interbankSettlementCurrency: "CAD",
    paymentInformationId: `k6-pay-${suffix}`,
    paymentMethod: "TRF",
    instructionPriority: "NORM",
    requestedExecutionDate: day,
    debtor: null,
    debtorAccount: { iban: null, other: debtorAccountId, currency: "CAD" },
    debtorAgent: null,
    creditor: null,
    creditorAccount: { iban: null, other: creditorAccountId, currency: "CAD" },
    creditorAgent: null,
    creditTransferTransactionInformation: {
      instructionId: `k6-instr-${suffix}`,
      endToEndId: `k6-e2e-${suffix}`,
      transactionId: `k6-txn-${suffix}`,
      paymentTypeInformation: "RTP",
      instructedAmount: instructedAmountStr,
      instructedCurrency: "CAD",
      chargeBearer: "DEBT",
      remittanceInformation: { unstructured: "RTP payment via K6 load test", reference: "K6" },
      valueDate: day,
      localInstrument: "RTP",
    },
  });
}

/** Alias naming from checkpoint spec — RTP / CAD always. */
export function generateBianPayload(debtorAccountId, creditorAccountId, _indicatorIgnored) {
  const amt = randomAmount(1.0, 9999.99);
  return buildLedgerPostPayload(debtorAccountId, creditorAccountId, amt, "bian");
}
