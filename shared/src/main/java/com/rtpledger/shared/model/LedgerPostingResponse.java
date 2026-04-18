package com.rtpledger.shared.model;

import java.util.Objects;

public record LedgerPostingResponse(
        String correlationId,
        String status
) {
    public LedgerPostingResponse {
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(status, "status is required");
    }
}
