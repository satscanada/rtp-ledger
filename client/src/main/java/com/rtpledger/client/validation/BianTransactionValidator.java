package com.rtpledger.client.validation;

import com.rtpledger.shared.model.BianCreditTransferTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

@Service
public class BianTransactionValidator {

    public List<String> validate(BianCreditTransferTransaction tx) {
        List<String> errors = new ArrayList<>();
        if (tx == null) {
            errors.add("body: request body is required");
            return errors;
        }

        BianCreditTransferTransaction.CreditTransferTransactionInformation ctti = tx.creditTransferTransactionInformation();
        if (ctti == null) {
            errors.add("creditTransferTransactionInformation: required");
            return errors;
        }

        if (isBlank(ctti.endToEndId())) {
            errors.add("endToEndId: must not be null or blank");
        }

        BigDecimal amount = ctti.instructedAmount();
        if (amount == null) {
            errors.add("instructedAmount.amount: required");
        } else if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("instructedAmount.amount: must be positive");
        }

        String currency = ctti.instructedCurrency();
        if (isBlank(currency)) {
            errors.add("instructedAmount.currency: required");
        } else if (!isValidIso4217(currency.trim())) {
            errors.add("instructedAmount.currency: must be a valid ISO 4217 code");
        }

        String debtorId = accountIdentifier(tx.debtorAccount());
        if (isBlank(debtorId)) {
            errors.add("debtorAccount.accountId: required (use debtorAccount.other or debtorAccount.iban)");
        }

        String creditorId = accountIdentifier(tx.creditorAccount());
        if (isBlank(creditorId)) {
            errors.add("creditorAccount.accountId: required (use creditorAccount.other or creditorAccount.iban)");
        }

        if (debtorId != null && creditorId != null && debtorId.equals(creditorId)) {
            errors.add("accounts: debtor and creditor must differ");
        }

        if (isBlank(ctti.localInstrument())) {
            errors.add("localInstrument: required");
        }

        if (isBlank(tx.paymentInformationId())) {
            errors.add("paymentInformationId: must not be null or blank");
        }

        return errors;
    }

    private static String accountIdentifier(BianCreditTransferTransaction.CashAccount account) {
        if (account == null) {
            return null;
        }
        if (!isBlank(account.other())) {
            return account.other().trim();
        }
        if (!isBlank(account.iban())) {
            return account.iban().trim();
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isValidIso4217(String code) {
        if (code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
