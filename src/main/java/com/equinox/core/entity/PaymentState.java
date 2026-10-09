package com.equinox.core.entity;

public enum PaymentState {
    PENDING,
    PROCESSING,
    SETTLED,
    FAILED,
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    DISPUTED
}
