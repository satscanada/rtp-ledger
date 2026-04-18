package com.rtpledger.server.util;

import com.rtpledger.shared.model.BianCreditTransferTransaction;

public final class AccountIdentifiers {

    private AccountIdentifiers() {
    }

    public static String fromCashAccount(BianCreditTransferTransaction.CashAccount account) {
        if (account == null) {
            return null;
        }
        if (account.other() != null && !account.other().isBlank()) {
            return account.other().trim();
        }
        if (account.iban() != null && !account.iban().isBlank()) {
            return account.iban().trim();
        }
        return null;
    }
}
