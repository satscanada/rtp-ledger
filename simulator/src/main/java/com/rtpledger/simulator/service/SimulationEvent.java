package com.rtpledger.simulator.service;

import java.time.OffsetDateTime;

public record SimulationEvent(
        OffsetDateTime timestamp,
        String scenario,
        String message,
        long successCount,
        long failureCount,
        double tps
) {
}
