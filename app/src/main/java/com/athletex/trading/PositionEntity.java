package com.athletex.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's holding in one athlete market. Shares are not in the cash ledger; this row is
 * the share ledger. {@code lockedQuantity} is escrow for resting sell orders.
 */
@Entity
@Table(name = "positions")
public class PositionEntity {

    /** Owner of unsold IPO inventory; inserted by the V3 migration. */
    public static final UUID HOUSE_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "locked_quantity", nullable = false)
    private int lockedQuantity;

    /** Cost basis of the shares currently held, in cents. Average cost = total / quantity. */
    @Column(name = "total_cost_cents", nullable = false)
    private long totalCostCents;

    @Column(name = "realized_pnl_cents", nullable = false)
    private long realizedPnlCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PositionEntity() {
    }

    public PositionEntity(UUID userId, UUID marketId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.marketId = marketId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getLockedQuantity() {
        return lockedQuantity;
    }

    public int getAvailableQuantity() {
        return quantity - lockedQuantity;
    }

    public long getTotalCostCents() {
        return totalCostCents;
    }

    public long getRealizedPnlCents() {
        return realizedPnlCents;
    }

    public long averageCostCents() {
        return quantity == 0 ? 0 : Math.round((double) totalCostCents / quantity);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void lock(int shares) {
        if (shares > getAvailableQuantity()) {
            throw new InsufficientSharesException(marketId, shares, getAvailableQuantity());
        }
        lockedQuantity += shares;
        touch();
    }

    void unlock(int shares) {
        if (shares > lockedQuantity) {
            throw new IllegalStateException("cannot unlock %d shares, only %d locked".formatted(shares, lockedQuantity));
        }
        lockedQuantity -= shares;
        touch();
    }

    void acquire(int shares, long priceCents) {
        quantity += shares;
        totalCostCents += shares * priceCents;
        touch();
    }

    /** Releases {@code shares} that were locked for a sell and books the realized P&L against average cost. */
    void deliverLocked(int shares, long priceCents) {
        if (shares > lockedQuantity) {
            throw new IllegalStateException("cannot deliver %d shares, only %d locked".formatted(shares, lockedQuantity));
        }
        long costRemoved = Math.round((double) totalCostCents * shares / quantity);
        realizedPnlCents += shares * priceCents - costRemoved;
        totalCostCents -= costRemoved;
        quantity -= shares;
        lockedQuantity -= shares;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
