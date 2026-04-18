package com.rtpledger.simulator.service;

public record SimulationStartResult(
        boolean started,
        String scenario,
        String message
) {
}
