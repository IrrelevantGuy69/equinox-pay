package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The secure, random string that goes in the URL (e.g., "inv_789xyz")
    @Column(unique = true, nullable = false)
    private String slug;

    // The Merchant's Account ID where the money will go
    @Column(nullable = false)
    private UUID merchantAccountId;

    @Column(nullable = false)
    private BigDecimal amount;

    // PENDING, PAID, or EXPIRED
    @Column(nullable = false)
    private String status;

    private Instant createdAt;
    private Instant expiresAt;
    @Column(name = "customer_account_id")
    private UUID customerAccountId;
}
