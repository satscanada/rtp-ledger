package com.rtpledger.simulator.controller;

import com.rtpledger.simulator.service.RtpScenarioRunner;
import com.rtpledger.simulator.service.SimulationStartResult;
import com.rtpledger.simulator.service.SimulationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/simulate")
@RequiredArgsConstructor
public class SimulatorController {

    private final RtpScenarioRunner scenarioRunner;

    @PostMapping("/apple-pay-burst")
    public ResponseEntity<SimulationRequestResponse> applePayBurst() {
        return toResponse(scenarioRunner.startApplePayBurst());
    }

    @PostMapping("/google-pay-mixed")
    public ResponseEntity<SimulationRequestResponse> googlePayMixed() {
        return toResponse(scenarioRunner.startGooglePayMixed());
    }

    @PostMapping("/single-account-drain")
    public ResponseEntity<SimulationRequestResponse> singleAccountDrain() {
        return toResponse(scenarioRunner.startSingleAccountDrain());
    }

    @GetMapping("/status")
    public SimulationStatus status() {
        return scenarioRunner.status();
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return scenarioRunner.subscribeEvents();
    }

    private ResponseEntity<SimulationRequestResponse> toResponse(SimulationStartResult result) {
        HttpStatus status = result.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new SimulationRequestResponse(
                result.started() ? "ACCEPTED" : "REJECTED",
                result.scenario(),
                result.message(),
                OffsetDateTime.now().toString()
        ));
    }

    public record SimulationRequestResponse(
            String status,
            String scenario,
            String message,
            String timestamp
    ) {
    }
}
