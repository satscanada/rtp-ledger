package com.rtpledger.server.config;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.rtpledger.server.disruptor.LedgerEventHandler;
import com.rtpledger.server.disruptor.LedgerServerEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;

@Configuration
@RequiredArgsConstructor
public class ServerDisruptorConfig implements DisposableBean {

    private final RtpServerProperties properties;
    private final LedgerEventHandler ledgerEventHandler;

    private Disruptor<LedgerServerEvent> disruptor;

    @Bean
    public RingBuffer<LedgerServerEvent> ledgerRingBuffer() {
        int size = properties.getDisruptor().getRingBufferSize();
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName(properties.getDisruptor().getThreadName() + "-0");
            return t;
        };
        disruptor = new Disruptor<>(
                LedgerServerEvent::new,
                size,
                threadFactory,
                ProducerType.MULTI,
                new BlockingWaitStrategy()
        );
        disruptor.handleEventsWith(ledgerEventHandler);
        disruptor.start();
        return disruptor.getRingBuffer();
    }

    @Override
    public void destroy() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }
}
