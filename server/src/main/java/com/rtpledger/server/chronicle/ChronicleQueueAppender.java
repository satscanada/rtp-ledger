package com.rtpledger.server.chronicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChronicleQueueAppender {

    private final SingleChronicleQueue ledgerQueue;
    private final ObjectMapper objectMapper;

    /**
     * Persists posting payload JSON and returns the Chronicle Queue index for this excerpt.
     */
    public long appendPostingPayload(PostingResult posting) {
        try {
            String json = objectMapper.writeValueAsString(posting);
            ExcerptAppender appender = ledgerQueue.acquireAppender();
            try (var dc = appender.writingDocument()) {
                dc.wire().write("postingJson").text(json);
            }
            return appender.lastIndexAppended();
        } catch (Exception e) {
            throw new IllegalStateException("Chronicle Queue append failed", e);
        }
    }
}
