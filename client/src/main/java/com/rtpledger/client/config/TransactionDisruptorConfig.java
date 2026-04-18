package com.rtpledger.client.config;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.rtpledger.client.disruptor.NatsPublishEventHandler;
import com.rtpledger.client.disruptor.TransactionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;

@Configuration
@RequiredArgsConstructor
public class TransactionDisruptorConfig implements DisposableBean {

    private final RtpClientProperties properties;
    private final NatsPublishEventHandler natsPublishEventHandler;

    private Disruptor<TransactionEvent> disruptor;

    @Bean
    public RingBuffer<TransactionEvent> transactionRingBuffer() {
        int size = properties.getDisruptor().getRingBufferSize();
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r);
            t.setName(properties.getDisruptor().getThreadName());
            return t;
        };
        disruptor = new Disruptor<>(
                TransactionEvent::new,
                size,
                threadFactory,
                ProducerType.SINGLE,
                new BlockingWaitStrategy()
        );
        disruptor.handleEventsWith(natsPublishEventHandler);
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
