package com.rtpledger.simulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "rtp.simulator")
public class RtpSimulatorProperties {

    private String clientBaseUrl = "http://localhost:18080";
    private String region = "ca-east";
    private String hotAccountId = "b7592f32-d833-52f5-83c4-1c2f367e52ab";
    private String counterpartyAccountId = "cfd7162e-d0d5-5381-829d-aae67da1d872";
    private int connectTimeoutMs = 1000;
    private int requestTimeoutMs = 3000;

    private List<String> mixedAccountIds = List.of(
            "b7592f32-d833-52f5-83c4-1c2f367e52ab",
            "0a99d33f-2eef-5aa8-a2e1-0eae24f67164",
            "f977dec6-138c-557b-b8a3-fee607ea307f",
            "e7a8ce09-4312-5d97-b969-559b083144dc",
            "77c0ee6b-7557-5ada-878d-69b51d0b4ee9",
            "f68cb1a1-805d-5d57-97cf-1ffdd32c7b17",
            "0c756ce1-d521-5cdf-9d61-dc20fa910b26",
            "2bc4db1f-78da-5c30-b6bd-b02e7c78262a",
            "d7890ade-09e0-5070-a6a9-329617b3811a",
            "1c43fb88-2c21-519b-bb33-b6c1569349e9"
    );

    private final Nats nats = new Nats();
    private final ApplePayBurst applePayBurst = new ApplePayBurst();
    private final GooglePayMixed googlePayMixed = new GooglePayMixed();
    private final SingleAccountDrain singleAccountDrain = new SingleAccountDrain();

    @Getter
    @Setter
    public static class Nats {
        private String servers = "nats://localhost:4222";
        private int connectionTimeoutMs = 5000;
    }

    @Getter
    @Setter
    public static class ApplePayBurst {
        private int cycles = 3;
        private int transactionsPerBurst = 500;
        private int burstDurationMs = 2000;
        private int lullDurationMs = 3000;
        private BigDecimal minAmount = new BigDecimal("1.00");
        private BigDecimal maxAmount = new BigDecimal("25.00");
    }

    @Getter
    @Setter
    public static class GooglePayMixed {
        private int virtualUsers = 200;
        private int runDurationSeconds = 60;
        private BigDecimal minAmount = new BigDecimal("1.00");
        private BigDecimal maxAmount = new BigDecimal("500.00");
    }

    @Getter
    @Setter
    public static class SingleAccountDrain {
        private int transactionCount = 1000;
        private BigDecimal amount = new BigDecimal("1.00");
    }
}
