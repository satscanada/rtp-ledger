package com.rtpledger.client.trace;

import com.rtpledger.client.config.RtpClientProperties;
import com.rtpledger.shared.message.FlowTraceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class FlowTraceStore {

    private final RtpClientProperties properties;
    private final ConcurrentMap<String, TimelineEntry> timelines = new ConcurrentHashMap<>();

    public void ingest(FlowTraceEvent event) {
        if (event == null || event.correlationId() == null || event.correlationId().isBlank()) {
            return;
        }
        TimelineEntry entry = timelines.computeIfAbsent(event.correlationId(), ignore -> new TimelineEntry());
        synchronized (entry) {
            entry.events().add(event);
            if (entry.events().size() > properties.getTrace().getMaxEventsPerCorrelation()) {
                int overflow = entry.events().size() - properties.getTrace().getMaxEventsPerCorrelation();
                entry.events().subList(0, overflow).clear();
            }
            entry.lastUpdatedAt = System.currentTimeMillis();
        }
        prune();
    }

    public List<FlowTraceEvent> timeline(String correlationId) {
        TimelineEntry entry = timelines.get(correlationId);
        if (entry == null) {
            return List.of();
        }
        synchronized (entry) {
            return entry.events().stream()
                    .sorted(Comparator.comparing(FlowTraceEvent::timestamp,
                            Comparator.nullsLast(String::compareTo)))
                    .toList();
        }
    }

    public List<RecentTraceView> recent() {
        long now = System.currentTimeMillis();
        long retentionMillis = properties.getTrace().getRetentionMinutes() * 60_000L;
        int limit = Math.max(1, properties.getTrace().getRecentLimit());
        Set<Map.Entry<String, TimelineEntry>> sorted = new LinkedHashSet<>(
                timelines.entrySet().stream()
                        .filter(e -> now - e.getValue().lastUpdatedAt <= retentionMillis)
                        .sorted((a, b) -> Long.compare(b.getValue().lastUpdatedAt, a.getValue().lastUpdatedAt))
                        .limit(limit)
                        .toList()
        );

        List<RecentTraceView> rows = new ArrayList<>(sorted.size());
        for (Map.Entry<String, TimelineEntry> item : sorted) {
            TimelineEntry entry = item.getValue();
            synchronized (entry) {
                if (entry.events().isEmpty()) {
                    continue;
                }
                FlowTraceEvent last = entry.events().get(entry.events().size() - 1);
                rows.add(new RecentTraceView(
                        item.getKey(),
                        entry.events().size(),
                        last.stage() != null ? last.stage().name() : null,
                        last.status(),
                        last.timestamp()
                ));
            }
        }
        return rows;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        long retentionMillis = properties.getTrace().getRetentionMinutes() * 60_000L;
        int maxCorrelations = Math.max(1, properties.getTrace().getMaxCorrelations());

        timelines.entrySet().removeIf(e -> now - e.getValue().lastUpdatedAt > retentionMillis);
        if (timelines.size() <= maxCorrelations) {
            return;
        }

        List<Map.Entry<String, TimelineEntry>> byAge = timelines.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastUpdatedAt))
                .toList();
        int toRemove = timelines.size() - maxCorrelations;
        for (int i = 0; i < toRemove && i < byAge.size(); i++) {
            timelines.remove(byAge.get(i).getKey());
        }
    }

    private static final class TimelineEntry {
        private final List<FlowTraceEvent> events = new ArrayList<>();
        private volatile long lastUpdatedAt = System.currentTimeMillis();

        private List<FlowTraceEvent> events() {
            return events;
        }
    }

    public record RecentTraceView(
            String correlationId,
            int eventCount,
            String lastStage,
            String lastStatus,
            String lastTimestamp
    ) {
    }
}
