package com.equinox.core.service;

import com.equinox.core.dto.BankSettlementRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
public class BankFileParserService {

    /**
     * Streams CSV rows one-by-one to maintain an O(1) heap memory footprint.
     * Expected schema: bank_reference,transaction_id,amount,currency,settled_at,type
     */
    public void parseStreaming(Reader reader, Consumer<BankSettlementRecord> recordConsumer) {
        try (BufferedReader br = (reader instanceof BufferedReader b) ? b : new BufferedReader(reader)) {
            String line;
            int lineNumber = 0;
            boolean isHeader = true;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (isHeader) {
                    isHeader = false;
                    if (line.toLowerCase().startsWith("bank_reference") || line.toLowerCase().startsWith("bankreference")) {
                        continue;
                    }
                }

                String[] parts = line.split(",", -1);
                if (parts.length < 3) {
                    log.warn("Skipping malformed row at line {}: {}", lineNumber, line);
                    continue;
                }

                String bankRef = parts[0].trim();
                String txId = parts[1].trim();
                String amountStr = parts[2].trim();
                String currency = parts.length > 3 ? parts[3].trim() : "USD";
                String settledAtStr = parts.length > 4 ? parts[4].trim() : null;
                String type = parts.length > 5 ? parts[5].trim() : "SETTLEMENT";

                BigDecimal amount;
                try {
                    amount = new BigDecimal(amountStr);
                } catch (Exception e) {
                    log.warn("Invalid amount on line {}: {}", lineNumber, amountStr);
                    continue;
                }

                Instant settledAt = Instant.now();
                if (settledAtStr != null && !settledAtStr.isEmpty()) {
                    try {
                        settledAt = Instant.parse(settledAtStr);
                    } catch (Exception e) {
                        settledAt = Instant.now();
                    }
                }

                BankSettlementRecord record = BankSettlementRecord.builder()
                        .bankReference(bankRef)
                        .transactionId(txId)
                        .amount(amount)
                        .currency(currency)
                        .settledAt(settledAt)
                        .type(type)
                        .lineNumber(lineNumber)
                        .build();

                recordConsumer.accept(record);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to stream parse bank settlement CSV", ex);
        }
    }

    public List<BankSettlementRecord> parseToList(String csvContent) {
        List<BankSettlementRecord> list = new ArrayList<>();
        parseStreaming(new StringReader(csvContent), list::add);
        return list;
    }
}
