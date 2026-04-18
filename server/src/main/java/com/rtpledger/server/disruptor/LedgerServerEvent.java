package com.rtpledger.server.disruptor;

import com.rtpledger.shared.message.LedgerPostingMessage;

public final class LedgerServerEvent {

    private LedgerPostingMessage payload;
    private String replyTo;

    public void clear() {
        this.payload = null;
        this.replyTo = null;
    }

    public void load(LedgerPostingMessage payload, String replyTo) {
        this.payload = payload;
        this.replyTo = replyTo;
    }

    public LedgerPostingMessage getPayload() {
        return payload;
    }

    public String getReplyTo() {
        return replyTo;
    }
}
