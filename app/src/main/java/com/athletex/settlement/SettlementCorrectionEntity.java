package com.athletex.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_corrections")
public class SettlementCorrectionEntity {

    @Id
    private UUID id;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "old_value_cents", nullable = false)
    private long oldValueCents;

    @Column(name = "new_value_cents", nullable = false)
    private long newValueCents;

    @Column(name = "new_fantasy_points", nullable = false)
    private BigDecimal newFantasyPoints;

    @Column(name = "total_adjustment_cents", nullable = false)
    private long totalAdjustmentCents;

    @Column(nullable = false)
    private boolean applied;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SettlementCorrectionEntity() {
    }

    public SettlementCorrectionEntity(UUID marketId, String eventId, long oldValueCents, long newValueCents,
                                      BigDecimal newFantasyPoints, long totalAdjustmentCents, boolean applied,
                                      String reason) {
        this.id = UUID.randomUUID();
        this.marketId = marketId;
        this.eventId = eventId;
        this.oldValueCents = oldValueCents;
        this.newValueCents = newValueCents;
        this.newFantasyPoints = newFantasyPoints;
        this.totalAdjustmentCents = totalAdjustmentCents;
        this.applied = applied;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public String getEventId() {
        return eventId;
    }

    public long getOldValueCents() {
        return oldValueCents;
    }

    public long getNewValueCents() {
        return newValueCents;
    }

    public BigDecimal getNewFantasyPoints() {
        return newFantasyPoints;
    }

    public long getTotalAdjustmentCents() {
        return totalAdjustmentCents;
    }

    public boolean isApplied() {
        return applied;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
