package com.athletex.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransactionEntity() {
    }

    public LedgerTransactionEntity(String idempotencyKey, TransactionType type) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public TransactionType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
