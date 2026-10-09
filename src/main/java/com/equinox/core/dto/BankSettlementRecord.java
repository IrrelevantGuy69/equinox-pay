package com.equinox.core.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankSettlementRecord {
    private String bankReference;
    private String transactionId; // paymentId as string
    private BigDecimal amount;
    private String currency;
    private Instant settledAt;
    private String type;
    private int lineNumber;
}
