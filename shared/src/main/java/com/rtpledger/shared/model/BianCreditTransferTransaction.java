package com.rtpledger.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

public record BianCreditTransferTransaction(
        String messageId,
        OffsetDateTime creationDateTime,
        String numberOfTransactions,
        BigDecimal totalInterbankSettlementAmount,
        String interbankSettlementCurrency,
        String paymentInformationId,
        String paymentMethod,
        String instructionPriority,
        LocalDate requestedExecutionDate,
        Party debtor,
        CashAccount debtorAccount,
        FinancialInstitution debtorAgent,
        Party creditor,
        CashAccount creditorAccount,
        FinancialInstitution creditorAgent,
        CreditTransferTransactionInformation creditTransferTransactionInformation
) {
    public BianCreditTransferTransaction {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(creationDateTime, "creationDateTime is required");
        Objects.requireNonNull(totalInterbankSettlementAmount, "totalInterbankSettlementAmount is required");
        Objects.requireNonNull(interbankSettlementCurrency, "interbankSettlementCurrency is required");
        Objects.requireNonNull(paymentInformationId, "paymentInformationId is required");
        Objects.requireNonNull(creditTransferTransactionInformation, "creditTransferTransactionInformation is required");

        totalInterbankSettlementAmount = totalInterbankSettlementAmount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public record CreditTransferTransactionInformation(
            String instructionId,
            String endToEndId,
            String transactionId,
            String paymentTypeInformation,
            BigDecimal instructedAmount,
            String instructedCurrency,
            String chargeBearer,
            RemittanceInformation remittanceInformation,
            LocalDate valueDate,
            String localInstrument
    ) {
        public CreditTransferTransactionInformation {
            Objects.requireNonNull(instructionId, "instructionId is required");
            Objects.requireNonNull(endToEndId, "endToEndId is required");
            Objects.requireNonNull(transactionId, "transactionId is required");
            Objects.requireNonNull(instructedAmount, "instructedAmount is required");
            Objects.requireNonNull(instructedCurrency, "instructedCurrency is required");
            Objects.requireNonNull(localInstrument, "localInstrument is required");

            instructedAmount = instructedAmount.setScale(2, RoundingMode.HALF_EVEN);
        }
    }

    public record Party(
            String name,
            String identification,
            PostalAddress postalAddress
    ) {
    }

    public record CashAccount(
            String iban,
            String other,
            String currency
    ) {
    }

    public record FinancialInstitution(
            String bicfi,
            String clearingSystemMemberId
    ) {
    }

    public record RemittanceInformation(
            String unstructured,
            String reference
    ) {
    }

    public record PostalAddress(
            String streetName,
            String buildingNumber,
            String postCode,
            String townName,
            String countrySubDivision,
            String country
    ) {
    }
}
