package com.athletex.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Snapshot of who held what at settlement; corrections are applied against these rows. */
@Entity
@Table(name = "settlement_payouts")
public class SettlementPayoutEntity {

    @Id
    private UUID id;

    @Column(name = "settlement_run_id", nullable = false)
    private UUID runId;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "value_cents", nullable = false)
    private long valueCents;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    protected SettlementPayoutEntity() {
    }

    public SettlementPayoutEntity(UUID runId, UUID marketId, UUID userId, int quantity, long valueCents) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.marketId = marketId;
        this.userId = userId;
        this.quantity = quantity;
        this.valueCents = valueCents;
        this.amountCents = quantity * valueCents;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getValueCents() {
        return valueCents;
    }

    public long getAmountCents() {
        return amountCents;
    }
}
