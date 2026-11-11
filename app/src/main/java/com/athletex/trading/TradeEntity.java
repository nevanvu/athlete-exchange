package com.athletex.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trades")
public class TradeEntity {

    @Id
    private UUID id;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "taker_order_id", nullable = false)
    private UUID takerOrderId;

    @Column(name = "maker_order_id", nullable = false)
    private UUID makerOrderId;

    @Column(name = "buyer_user_id", nullable = false)
    private UUID buyerUserId;

    @Column(name = "seller_user_id", nullable = false)
    private UUID sellerUserId;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    protected TradeEntity() {
    }

    public TradeEntity(UUID marketId, UUID takerOrderId, UUID makerOrderId,
                       UUID buyerUserId, UUID sellerUserId, long priceCents, int quantity) {
        this.id = UUID.randomUUID();
        this.marketId = marketId;
        this.takerOrderId = takerOrderId;
        this.makerOrderId = makerOrderId;
        this.buyerUserId = buyerUserId;
        this.sellerUserId = sellerUserId;
        this.priceCents = priceCents;
        this.quantity = quantity;
        this.executedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public UUID getTakerOrderId() {
        return takerOrderId;
    }

    public UUID getMakerOrderId() {
        return makerOrderId;
    }

    public UUID getBuyerUserId() {
        return buyerUserId;
    }

    public UUID getSellerUserId() {
        return sellerUserId;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
