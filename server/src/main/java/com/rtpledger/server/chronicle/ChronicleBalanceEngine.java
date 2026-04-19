package com.rtpledger.server.chronicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.shared.message.LedgerPostingMessage;
import com.rtpledger.shared.model.BianCreditTransferTransaction;
import com.rtpledger.shared.model.BalanceResponse;
import com.rtpledger.server.metrics.ServerMetrics;
import com.rtpledger.server.util.AccountIdentifiers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.map.ChronicleMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChronicleBalanceEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChronicleMap<String, String> balanceMap;
    private final ServerMetrics serverMetrics;

    @Value("${rtp.default-currency:CAD}")
    private String defaultCurrency;

    public PostingResult applyPosting(LedgerPostingMessage message) {
        BianCreditTransferTransaction tx = message.transaction();
        BianCreditTransferTransaction.CreditTransferTransactionInformation ctti = tx.creditTransferTransactionInformation();

        String pathAccountId = message.accountId().trim();
        String debtorId = AccountIdentifiers.fromCashAccount(tx.debtorAccount());
        String creditorId = AccountIdentifiers.fromCashAccount(tx.creditorAccount());
        BigDecimal amount = ctti.instructedAmount();
        String currency = ctti.instructedCurrency().trim();

        String indicator;
        BigDecimal signedDelta;
        if (pathAccountId.equals(creditorId)) {
            indicator = "CRDT";
            signedDelta = amount;
        } else if (pathAccountId.equals(debtorId)) {
            indicator = "DBIT";
            signedDelta = amount.negate();
        } else {
            throw new IllegalArgumentException(
                    "accountId in subject must match debtorAccount or creditorAccount (other/iban)"
            );
        }

        UUID accountUuid = UUID.fromString(pathAccountId);

        BigDecimal[] previousHolder = new BigDecimal[1];
        BigDecimal[] currentHolder = new BigDecimal[1];

        balanceMap.compute(pathAccountId, (key, existingJson) -> {
            BigDecimal previous = parseAmount(existingJson).setScale(2, RoundingMode.HALF_EVEN);
            previousHolder[0] = previous;
            BigDecimal current = previous.add(signedDelta).setScale(2, RoundingMode.HALF_EVEN);
            currentHolder[0] = current;
            return serializeBalance(current, currency);
        });

        BigDecimal previousBalance = previousHolder[0];
        BigDecimal currentBalance = currentHolder[0];

        LocalDate valueDate = ctti.valueDate() != null ? ctti.valueDate() : LocalDate.now();
        LocalDate bookingDate = LocalDate.now();

        return new PostingResult(
                UUID.randomUUID(),
                accountUuid,
                message.correlationId(),
                ctti.endToEndId(),
                tx.paymentInformationId(),
                indicator,
                amount,
                currency,
                previousBalance,
                currentBalance,
                valueDate,
                bookingDate,
                ctti.localInstrument(),
                "POSTED",
                OffsetDateTime.now()
        );
    }

    public BalanceResponse readBalance(UUID accountId) {
        String key = accountId.toString();
        String json = balanceMap.get(key);
        if (json == null) {
            return new BalanceResponse(
                    accountId,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                    defaultCurrency,
                    OffsetDateTime.now()
            );
        }
        try {
            JsonNode node = JSON.readTree(json);
            BigDecimal amt = new BigDecimal(node.get("amt").asText()).setScale(2, RoundingMode.HALF_EVEN);
            String ccy = node.has("ccy") ? node.get("ccy").asText() : defaultCurrency;
            return new BalanceResponse(accountId, amt, ccy, OffsetDateTime.now());
        } catch (Exception e) {
            log.error("Corrupt Chronicle Map balance entry for accountId={} — returning zero balance; investigate immediately", key, e);
            serverMetrics.incrementBalanceCorruptionDetected();
            return new BalanceResponse(
                    accountId,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                    defaultCurrency,
                    OffsetDateTime.now()
            );
        }
    }

    private static BigDecimal parseAmount(String json) {
        if (json == null || json.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return new BigDecimal(node.get("amt").asText());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String serializeBalance(BigDecimal amount, String currency) {
        try {
            return JSON.createObjectNode()
                    .put("amt", amount.toPlainString())
                    .put("ccy", currency)
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
