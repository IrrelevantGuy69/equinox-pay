package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mandates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mandate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_account_id", nullable = false)
    private Account payerAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_account_id", nullable = false)
    private Account merchantAccount;

    @Column(name = "max_debit_cap", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxDebitCap;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MandateFrequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MandateStatus status;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "last_billed_at")
    private Instant lastBilledAt;

    @Column(name = "next_billing_date")
    private Instant nextBillingDate;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
