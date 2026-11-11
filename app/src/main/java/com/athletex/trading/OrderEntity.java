package com.athletex.trading;

import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType type;

    @Column(name = "price_cents")
    private Long priceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    /** Cash still locked in the user's escrow account for this (buy) order. */
    @Column(name = "escrow_cents", nullable = false)
    private long escrowCents;

    /** Shares still locked in the user's position for this (sell) order. */
    @Column(name = "locked_shares", nullable = false)
    private int lockedShares;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderEntity() {
    }

    public OrderEntity(UUID userId, UUID marketId, Side side, OrderType type, Long priceCents, int quantity) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.marketId = marketId;
        this.side = side;
        this.type = type;
        this.priceCents = priceCents;
        this.quantity = quantity;
        this.filledQuantity = 0;
        this.status = OrderStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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

    public Side getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public int getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getEscrowCents() {
        return escrowCents;
    }

    public int getLockedShares() {
        return lockedShares;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void recordFill(int quantity) {
        this.filledQuantity += quantity;
        this.status = filledQuantity == this.quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        touch();
    }

    void setEscrowCents(long escrowCents) {
        this.escrowCents = escrowCents;
        touch();
    }

    void setLockedShares(int lockedShares) {
        this.lockedShares = lockedShares;
        touch();
    }

    void cancel() {
        this.status = OrderStatus.CANCELLED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
