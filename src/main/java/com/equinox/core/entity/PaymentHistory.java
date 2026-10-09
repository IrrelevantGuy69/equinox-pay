package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state")
    private PaymentState previousState;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_state", nullable = false, updatable = false)
    private PaymentState newState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
