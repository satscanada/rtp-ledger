package com.rtpledger.simulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.shared.model.BalanceResponse;
import com.rtpledger.shared.model.BianCreditTransferTransaction;
import com.rtpledger.simulator.config.RtpSimulatorProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class RtpScenarioRunner {

    private static final String APPLE_PAY_BURST = "apple-pay-burst";
    private static final String GOOGLE_PAY_MIXED = "google-pay-mixed";
    private static final String SINGLE_ACCOUNT_DRAIN = "single-account-drain";

    private final RtpSimulatorProperties properties;
    private final ObjectMapper objectMapper;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicReference<ScenarioRun> activeRun = new AtomicReference<>();

    private volatile String latestMessage = "idle";
    private volatile String lastScenario = "none";
    private volatile long lastElapsedSeconds = 0L;
    private volatile long lastSuccessCount = 0L;
    private volatile long lastFailureCount = 0L;
    private volatile double lastTps = 0.0d;
    private HttpClient httpClient;

    @PostConstruct
    void initializeHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public SimulationStartResult startApplePayBurst() {
        return startScenario(APPLE_PAY_BURST, this::runApplePayBurst);
    }

    public SimulationStartResult startGooglePayMixed() {
        return startScenario(GOOGLE_PAY_MIXED, this::runGooglePayMixed);
    }

    public SimulationStartResult startSingleAccountDrain() {
        return startScenario(SINGLE_ACCOUNT_DRAIN, this::runSingleAccountDrain);
    }

    public SimulationStatus status() {
        ScenarioRun run = activeRun.get();
        if (run == null) {
            return new SimulationStatus(
                    false,
                    lastScenario,
                    lastTps,
                    lastElapsedSeconds,
                    lastSuccessCount,
                    lastFailureCount,
                    latestMessage
            );
        }
        return new SimulationStatus(
                true,
                run.scenario(),
                run.tps(),
                run.elapsedSeconds(),
                run.successCount().get(),
                run.failureCount().get(),
                latestMessage
        );
    }

    public SseEmitter subscribeEvents() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ex -> emitters.remove(emitter));
        emitStatusEvent("subscriber-connected");
        return emitter;
    }

    private SimulationStartResult startScenario(String scenario, ScenarioExecution execution) {
        ScenarioRun run = new ScenarioRun(
                scenario,
                Instant.now(),
                Executors.newVirtualThreadPerTaskExecutor(),
                new AtomicLong(),
                new AtomicLong()
        );
        if (!activeRun.compareAndSet(null, run)) {
            ScenarioRun current = activeRun.get();
            String activeScenario = current == null ? "unknown" : current.scenario();
            return new SimulationStartResult(false, activeScenario, "Scenario already running");
        }

        latestMessage = "Scenario started";
        emitStatusEvent("Scenario started");
        run.executor().submit(() -> {
            try {
                execution.run(run);
                latestMessage = "Scenario completed";
                emitStatusEvent("Scenario completed");
            } catch (Exception ex) {
                run.failureCount().incrementAndGet();
                latestMessage = "Scenario failed: " + ex.getMessage();
                log.error("Scenario {} failed", scenario, ex);
                emitStatusEvent(latestMessage);
            } finally {
                lastScenario = run.scenario();
                lastElapsedSeconds = run.elapsedSeconds();
                lastSuccessCount = run.successCount().get();
                lastFailureCount = run.failureCount().get();
                lastTps = run.tps();
                activeRun.compareAndSet(run, null);
                run.executor().shutdown();
            }
        });
        return new SimulationStartResult(true, scenario, "Scenario accepted");
    }

    private void runApplePayBurst(ScenarioRun run) throws InterruptedException {
        var config = properties.getApplePayBurst();
        String accountId = properties.getHotAccountId();
        for (int cycle = 1; cycle <= config.getCycles(); cycle++) {
            emitStatusEvent("Cycle " + cycle + " burst started");
            runTimedBurst(
                    run,
                    accountId,
                    config.getTransactionsPerBurst(),
                    config.getBurstDurationMs(),
                    config.getMinAmount(),
                    config.getMaxAmount(),
                    "apple-cycle-" + cycle
            );
            if (cycle < config.getCycles()) {
                emitStatusEvent("Cycle " + cycle + " lull");
                Thread.sleep(config.getLullDurationMs());
            }
        }
    }

    private void runGooglePayMixed(ScenarioRun run) throws InterruptedException {
        var config = properties.getGooglePayMixed();
        List<String> accounts = properties.getMixedAccountIds();
        Instant deadline = Instant.now().plusSeconds(config.getRunDurationSeconds());
        CountDownLatch latch = new CountDownLatch(config.getVirtualUsers());
        for (int i = 0; i < config.getVirtualUsers(); i++) {
            run.executor().submit(() -> {
                try {
                    while (Instant.now().isBefore(deadline) && !Thread.currentThread().isInterrupted()) {
                        String accountId = accounts.get(ThreadLocalRandom.current().nextInt(accounts.size()));
                        BigDecimal amount = randomAmount(config.getMinAmount(), config.getMaxAmount());
                        recordResult(run, postTransaction(accountId, amount, "google-pay"));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    private void runSingleAccountDrain(ScenarioRun run) {
        var config = properties.getSingleAccountDrain();
        String accountId = properties.getHotAccountId();
        BigDecimal startingBalance = fetchBalance(accountId);
        if (startingBalance == null) {
            run.failureCount().incrementAndGet();
            emitStatusEvent("Failed to fetch starting balance");
            return;
        }

        for (int i = 1; i <= config.getTransactionCount(); i++) {
            recordResult(run, postTransaction(accountId, config.getAmount(), "single-drain"));
            if (i % 100 == 0) {
                emitStatusEvent("Processed " + i + " / " + config.getTransactionCount());
            }
        }

        BigDecimal endingBalance = fetchBalance(accountId);
        if (endingBalance == null) {
            run.failureCount().incrementAndGet();
            emitStatusEvent("Failed to fetch ending balance");
            return;
        }

        BigDecimal expectedBalance = startingBalance
                .add(config.getAmount().setScale(2, RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(config.getTransactionCount())));
        if (endingBalance.compareTo(expectedBalance) == 0) {
            emitStatusEvent("Balance verification passed");
        } else {
            run.failureCount().incrementAndGet();
            emitStatusEvent("Balance verification failed. expected=" + expectedBalance + ", actual=" + endingBalance);
        }
    }

    private void runTimedBurst(
            ScenarioRun run,
            String accountId,
            int transactions,
            int durationMs,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String idPrefix
    ) throws InterruptedException {
        Instant start = Instant.now();
        CountDownLatch latch = new CountDownLatch(transactions);
        for (int i = 0; i < transactions; i++) {
            final int index = i;
            run.executor().submit(() -> {
                try {
                    long offsetMs = ((long) index * durationMs) / transactions;
                    sleepUntil(start.plusMillis(offsetMs));
                    recordResult(run, postTransaction(accountId, randomAmount(minAmount, maxAmount), idPrefix));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    run.failureCount().incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    private boolean postTransaction(String accountId, BigDecimal amount, String flowTag) {
        try {
            BianCreditTransferTransaction transaction = buildTransaction(accountId, amount, flowTag);
            URI uri = URI.create(properties.getClientBaseUrl()
                    + "/api/v1/ledger/"
                    + properties.getRegion()
                    + "/"
                    + accountId
                    + "/post");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(transaction)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 202;
        } catch (Exception ex) {
            log.debug("POST failed for scenario {}", flowTag, ex);
            return false;
        }
    }

    private BigDecimal fetchBalance(String accountId) {
        try {
            URI uri = URI.create(properties.getClientBaseUrl()
                    + "/api/v1/ledger/"
                    + properties.getRegion()
                    + "/"
                    + accountId
                    + "/balance");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            BalanceResponse balanceResponse = objectMapper.readValue(response.body(), BalanceResponse.class);
            return balanceResponse.balance();
        } catch (Exception ex) {
            log.debug("Balance fetch failed for {}", accountId, ex);
            return null;
        }
    }

    private BianCreditTransferTransaction buildTransaction(String accountId, BigDecimal amount, String flowTag) {
        String debtorAccountId = resolveDebtorAccountId(accountId);
        String token = UUID.randomUUID().toString();
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_EVEN);
        return new BianCreditTransferTransaction(
                "msg-" + flowTag + "-" + token,
                OffsetDateTime.now(),
                "1",
                scaled,
                "CAD",
                "pay-" + token,
                "TRF",
                "NORM",
                LocalDate.now(),
                null,
                new BianCreditTransferTransaction.CashAccount(null, debtorAccountId, "CAD"),
                null,
                null,
                new BianCreditTransferTransaction.CashAccount(null, accountId, "CAD"),
                null,
                new BianCreditTransferTransaction.CreditTransferTransactionInformation(
                        "instr-" + token,
                        "e2e-" + token,
                        "txn-" + token,
                        "RTP",
                        scaled,
                        "CAD",
                        "DEBT",
                        new BianCreditTransferTransaction.RemittanceInformation("Simulator transaction", flowTag),
                        LocalDate.now(),
                        "RTP"
                )
        );
    }

    private String resolveDebtorAccountId(String accountId) {
        if (!accountId.equals(properties.getCounterpartyAccountId())) {
            return properties.getCounterpartyAccountId();
        }
        return properties.getMixedAccountIds().stream()
                .filter(candidate -> !candidate.equals(accountId))
                .findFirst()
                .orElse("cfd7162e-d0d5-5381-829d-aae67da1d872");
    }

    private void recordResult(ScenarioRun run, boolean success) {
        if (success) {
            run.successCount().incrementAndGet();
        } else {
            run.failureCount().incrementAndGet();
        }
    }

    private BigDecimal randomAmount(BigDecimal min, BigDecimal max) {
        BigDecimal scaledMin = min.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal scaledMax = max.setScale(2, RoundingMode.HALF_EVEN);
        int minCents = scaledMin.movePointRight(2).intValueExact();
        int maxCents = scaledMax.movePointRight(2).intValueExact();
        int cents = ThreadLocalRandom.current().nextInt(minCents, maxCents + 1);
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.HALF_EVEN);
    }

    private void sleepUntil(Instant target) throws InterruptedException {
        long sleepMillis = Duration.between(Instant.now(), target).toMillis();
        if (sleepMillis > 0) {
            Thread.sleep(sleepMillis);
        }
    }

    private void emitStatusEvent(String message) {
        ScenarioRun run = activeRun.get();
        String scenario = run == null ? "none" : run.scenario();
        long success = run == null ? 0L : run.successCount().get();
        long failure = run == null ? 0L : run.failureCount().get();
        double tps = run == null ? 0.0d : run.tps();

        SimulationEvent event = new SimulationEvent(
                OffsetDateTime.now(),
                scenario,
                message,
                success,
                failure,
                tps
        );
        latestMessage = message;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("simulation").data(event));
            } catch (IOException ex) {
                emitter.completeWithError(ex);
                emitters.remove(emitter);
            }
        }
    }

    private record ScenarioRun(
            String scenario,
            Instant startedAt,
            ExecutorService executor,
            AtomicLong successCount,
            AtomicLong failureCount
    ) {
        private long elapsedSeconds() {
            return Duration.between(startedAt, Instant.now()).toSeconds();
        }

        private double tps() {
            double elapsed = Math.max(Duration.between(startedAt, Instant.now()).toMillis() / 1000.0d, 0.001d);
            return (successCount.get() + failureCount.get()) / elapsed;
        }
    }

    @FunctionalInterface
    private interface ScenarioExecution {
        void run(ScenarioRun run) throws Exception;
    }
}
