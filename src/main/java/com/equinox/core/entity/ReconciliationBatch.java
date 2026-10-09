package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_reference", nullable = false, unique = true)
    private String batchReference;

    @Column(name = "source_file_name")
    private String sourceFileName;

    @Column(name = "total_bank_records", nullable = false)
    private int totalBankRecords;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReconciliationBatchStatus status;

    @Column(name = "ledger_debits_credits_balanced", nullable = false)
    private boolean ledgerDebitsCreditsBalanced;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }
}
