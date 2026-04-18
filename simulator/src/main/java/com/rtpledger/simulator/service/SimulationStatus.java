package com.rtpledger.simulator.service;

public record SimulationStatus(
        boolean running,
        String activeScenario,
        double tps,
        long elapsedSeconds,
        long successCount,
        long failureCount,
        String latestMessage
) {
}
