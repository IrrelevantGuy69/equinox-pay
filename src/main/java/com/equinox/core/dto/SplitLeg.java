package com.equinox.core.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SplitLeg {
    private UUID merchantAccountId;
    private BigDecimal amount;
    private BigDecimal customFee; // Optional custom fee for this leg
}
