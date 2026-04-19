package com.rtpledger.client.controller;

import com.rtpledger.client.trace.FlowTraceStore;
import com.rtpledger.shared.message.FlowTraceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ledger/trace")
@RequiredArgsConstructor
public class FlowTraceController {

    private final FlowTraceStore flowTraceStore;

    @GetMapping("/{correlationId}")
    public TraceTimelineResponse timeline(@PathVariable String correlationId) {
        List<FlowTraceEvent> events = flowTraceStore.timeline(correlationId);
        return new TraceTimelineResponse(correlationId, events.size(), events);
    }

    @GetMapping("/recent")
    public List<FlowTraceStore.RecentTraceView> recent(
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        List<FlowTraceStore.RecentTraceView> rows = flowTraceStore.recent();
        if (limit == null || limit <= 0 || limit >= rows.size()) {
            return rows;
        }
        return rows.subList(0, limit);
    }

    public record TraceTimelineResponse(
            String correlationId,
            int eventCount,
            List<FlowTraceEvent> events
    ) {
    }
}
