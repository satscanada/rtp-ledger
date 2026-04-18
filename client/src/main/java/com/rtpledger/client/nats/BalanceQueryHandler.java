package com.rtpledger.client.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.client.config.RtpClientProperties;
import com.rtpledger.shared.model.BalanceResponse;
import io.nats.client.Connection;
import io.nats.client.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceQueryHandler {

    private final Connection natsConnection;
    private final ObjectMapper objectMapper;
    private final RtpClientProperties properties;

    public BalanceResponse query(String region, UUID accountId) {
        String prefix = properties.getNats().getSubjectPrefix();
        String subject = prefix + ".balance." + region + "." + accountId;
        int timeoutMs = properties.getNats().getBalanceRequestTimeoutMs();
        try {
            Message reply = natsConnection.request(subject, null, Duration.ofMillis(timeoutMs));
            if (reply == null) {
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "balance query timed out");
            }
            return objectMapper.readValue(reply.getData(), BalanceResponse.class);
        } catch (IOException e) {
            log.warn("balance query failed region={} accountId={}", region, accountId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "balance response could not be parsed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "balance query interrupted", e);
        }
    }
}
