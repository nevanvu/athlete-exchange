package com.athletex.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_runs")
public class SettlementRunEntity {

    @Id
    private UUID id;

    @Column(name = "slate_id", nullable = false, unique = true)
    private UUID slateId;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    @Column(name = "total_paid_cents", nullable = false)
    private long totalPaidCents;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    protected SettlementRunEntity() {
    }

    public SettlementRunEntity(UUID id, UUID slateId, Instant settledAt, long totalPaidCents) {
        this.id = id;
        this.slateId = slateId;
        this.settledAt = settledAt;
        this.totalPaidCents = totalPaidCents;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public long getTotalPaidCents() {
        return totalPaidCents;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    void setLedgerTransactionId(UUID ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
    }
}
