package com.athletex.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ipo_bids")
public class IpoBidEntity {

    public enum Status {
        OPEN,
        ALLOCATED,
        REFUNDED,
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "max_price_cents", nullable = false)
    private long maxPriceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "allocated_quantity", nullable = false)
    private int allocatedQuantity;

    @Column(name = "clearing_price_cents")
    private Long clearingPriceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IpoBidEntity() {
    }

    public IpoBidEntity(UUID userId, UUID marketId, long maxPriceCents, int quantity) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.marketId = marketId;
        this.maxPriceCents = maxPriceCents;
        this.quantity = quantity;
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
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

    public long getMaxPriceCents() {
        return maxPriceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public long escrowCents() {
        return maxPriceCents * quantity;
    }

    public int getAllocatedQuantity() {
        return allocatedQuantity;
    }

    public Long getClearingPriceCents() {
        return clearingPriceCents;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void allocate(int quantity, long clearingPriceCents) {
        this.allocatedQuantity = quantity;
        this.clearingPriceCents = clearingPriceCents;
        this.status = quantity > 0 ? Status.ALLOCATED : Status.REFUNDED;
    }

    void cancel() {
        this.status = Status.CANCELLED;
    }
}
