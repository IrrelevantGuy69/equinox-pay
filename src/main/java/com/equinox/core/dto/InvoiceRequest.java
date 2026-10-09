package com.equinox.core.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class InvoiceRequest {
    private BigDecimal amount;
}
