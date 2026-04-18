package com.rtpledger.server.drain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.server.chronicle.PostingResult;
import com.rtpledger.server.config.RtpServerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.wire.DocumentContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueDrainer {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SingleChronicleQueue ledgerQueue;
    private final RtpServerProperties properties;
    private final TailPointerRepository tailPointerRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerBalanceRepository ledgerBalanceRepository;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executor;
    private ExcerptTailer tailer;

    @PostConstruct
    public void start() {
        String serverId = properties.getDrainer().getServerId();
        var lastCommitted = tailPointerRepository.findCommittedIndex(serverId);
        tailer = ledgerQueue.createTailer("drainer-0");
        lastCommitted.ifPresentOrElse(
                last -> {
                    log.info("Tail pointer recovery: serverId={} lastCommittedIndex={} (seek to {})",
                            serverId, last, last + 1);
                    tailer.moveToIndex(last + 1);
                },
                () -> {
                    log.info("Tail pointer recovery: serverId={} no row (starting from queue start)", serverId);
                    tailer.toStart();
                }
        );

        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("rtp-drainer-0");
            return t;
        };
        executor = Executors.newSingleThreadExecutor(factory);
        executor.submit(this::drainLoop);
        log.info("QueueDrainer started (batchSize={}, flushIntervalMs={})",
                properties.getDrainer().getBatchSize(),
                properties.getDrainer().getFlushIntervalMs());
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("QueueDrainer executor did not terminate cleanly");
            }
        }
        if (tailer != null) {
            tailer.close();
        }
    }

    private void drainLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                boolean drained = drainOnceHybrid();
                if (!drained) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
            } catch (Exception e) {
                log.error("QueueDrainer iteration failed", e);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }
        }
    }

    /**
     * @return true if work was done (batch flushed or read attempted with progress)
     */
    private boolean drainOnceHybrid() throws Exception {
        int maxBatch = properties.getDrainer().getBatchSize();
        long flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(properties.getDrainer().getFlushIntervalMs());
        long deadline = System.nanoTime() + flushIntervalNanos;

        List<DrainItem> batch = new ArrayList<>();
        while (batch.size() < maxBatch && System.nanoTime() < deadline) {
            try (DocumentContext dc = tailer.readingDocument()) {
                if (!dc.isPresent()) {
                    break;
                }
                long index = tailer.index();
                String json = readPostingJson(dc);
                PostingResult posting = objectMapper.readValue(json, PostingResult.class);
                batch.add(new DrainItem(posting, index));
            }
        }

        if (batch.isEmpty()) {
            return false;
        }

        batch.sort(Comparator.comparingLong(DrainItem::chronicleIndex));
        flushBatch(batch);
        return true;
    }

    private static String readPostingJson(DocumentContext dc) {
        var wire = dc.wire();
        return wire.read("postingJson").text();
    }

    private void flushBatch(List<DrainItem> batch) throws SQLException {
        String serverId = properties.getDrainer().getServerId();
        long maxIndex = batch.stream().mapToLong(DrainItem::chronicleIndex).max().orElseThrow();

        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try {
            ledgerEntryRepository.insertBatch(connection, batch);
            ledgerBalanceRepository.upsertBatch(connection, batch);
            tailPointerRepository.upsertCommittedIndex(connection, serverId, maxIndex);
            connection.commit();
            log.debug("Drainer flushed batch size={} maxChronicleIndex={}", batch.size(), maxIndex);
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.close();
        }
    }
}
