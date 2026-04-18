package com.rtpledger.client.disruptor;

import com.rtpledger.shared.model.BianCreditTransferTransaction;

public final class TransactionEvent {

    private String correlationId;
    private String region;
    private String accountId;
    private BianCreditTransferTransaction transaction;

    public void clear() {
        this.correlationId = null;
        this.region = null;
        this.accountId = null;
        this.transaction = null;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public BianCreditTransferTransaction getTransaction() {
        return transaction;
    }

    public void setTransaction(BianCreditTransferTransaction transaction) {
        this.transaction = transaction;
    }
}
