package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Total balance = availableBalance + pendingBalance
    @Builder.Default
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    // Phase 8: Uncleared merchant revenues (T+1 clearing)
    @Builder.Default
    @Column(name = "pending_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    // Phase 8: Cleared, payout-eligible funds
    @Builder.Default
    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    public void syncBalances() {
        if (availableBalance == null) availableBalance = BigDecimal.ZERO;
        if (pendingBalance == null) pendingBalance = BigDecimal.ZERO;
        if (balance == null) balance = BigDecimal.ZERO;

        // If balance was set but split balances weren't initialized, treat balance as available
        if (balance.compareTo(BigDecimal.ZERO) > 0 && availableBalance.compareTo(BigDecimal.ZERO) == 0 && pendingBalance.compareTo(BigDecimal.ZERO) == 0) {
            availableBalance = balance;
        }
        balance = availableBalance.add(pendingBalance);
    }

    public void creditPending(BigDecimal amount) {
        if (this.pendingBalance == null) this.pendingBalance = BigDecimal.ZERO;
        this.pendingBalance = this.pendingBalance.add(amount);
        this.balance = (this.availableBalance != null ? this.availableBalance : BigDecimal.ZERO).add(this.pendingBalance);
    }

    public void debitPending(BigDecimal amount) {
        if (this.pendingBalance == null) this.pendingBalance = BigDecimal.ZERO;
        this.pendingBalance = this.pendingBalance.subtract(amount);
        this.balance = (this.availableBalance != null ? this.availableBalance : BigDecimal.ZERO).add(this.pendingBalance);
    }

    public void creditAvailable(BigDecimal amount) {
        if (this.availableBalance == null) this.availableBalance = BigDecimal.ZERO;
        this.availableBalance = this.availableBalance.add(amount);
        this.balance = this.availableBalance.add(this.pendingBalance != null ? this.pendingBalance : BigDecimal.ZERO);
    }

    public void debitAvailable(BigDecimal amount) {
        if (this.availableBalance == null) this.availableBalance = BigDecimal.ZERO;
        this.availableBalance = this.availableBalance.subtract(amount);
        this.balance = this.availableBalance.add(this.pendingBalance != null ? this.pendingBalance : BigDecimal.ZERO);
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance != null ? balance : BigDecimal.ZERO;
        if (this.pendingBalance == null || this.pendingBalance.compareTo(BigDecimal.ZERO) == 0) {
            this.availableBalance = this.balance;
        }
    }
}
