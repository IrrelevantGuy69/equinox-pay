package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_fee_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantFeePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_account_id", nullable = false, unique = true)
    private Account merchantAccount;

    // e.g., 0.0290 for 2.9%
    @Builder.Default
    @Column(name = "percentage_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal percentageRate = BigDecimal.ZERO;

    // e.g., 0.3000 for $0.30
    @Builder.Default
    @Column(name = "fixed_fee", nullable = false, precision = 19, scale = 4)
    private BigDecimal fixedFee = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Calculate platform fee using RoundingMode.HALF_EVEN (Banker's Rounding).
     * fee = (grossAmount * percentageRate) + fixedFee
     */
    public BigDecimal calculateFee(BigDecimal grossAmount) {
        if (grossAmount == null || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_EVEN);
        }
        BigDecimal variableFee = grossAmount.multiply(percentageRate != null ? percentageRate : BigDecimal.ZERO);
        BigDecimal totalFee = variableFee.add(fixedFee != null ? fixedFee : BigDecimal.ZERO);
        return totalFee.setScale(4, RoundingMode.HALF_EVEN);
    }
}
