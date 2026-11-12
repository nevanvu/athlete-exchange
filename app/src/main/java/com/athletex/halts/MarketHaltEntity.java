package com.athletex.halts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "market_halts")
public class MarketHaltEntity {

    @Id
    private UUID id;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "trigger_event_id")
    private String triggerEventId;

    @Column(name = "fair_value_before_cents")
    private Long fairValueBeforeCents;

    @Column(name = "fair_value_after_cents")
    private Long fairValueAfterCents;

    @Column(name = "halted_at", nullable = false)
    private Instant haltedAt;

    @Column(name = "resume_after")
    private Instant resumeAfter;

    @Column(name = "resumed_at")
    private Instant resumedAt;

    protected MarketHaltEntity() {
    }

    public MarketHaltEntity(UUID marketId, String reason, String triggerEventId, Long fairValueBeforeCents,
                            Long fairValueAfterCents, Instant haltedAt, Instant resumeAfter) {
        this.id = UUID.randomUUID();
        this.marketId = marketId;
        this.reason = reason;
        this.triggerEventId = triggerEventId;
        this.fairValueBeforeCents = fairValueBeforeCents;
        this.fairValueAfterCents = fairValueAfterCents;
        this.haltedAt = haltedAt;
        this.resumeAfter = resumeAfter;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public String getReason() {
        return reason;
    }

    public String getTriggerEventId() {
        return triggerEventId;
    }

    public Long getFairValueBeforeCents() {
        return fairValueBeforeCents;
    }

    public Long getFairValueAfterCents() {
        return fairValueAfterCents;
    }

    public Instant getHaltedAt() {
        return haltedAt;
    }

    public Instant getResumeAfter() {
        return resumeAfter;
    }

    public Instant getResumedAt() {
        return resumedAt;
    }

    void markResumed(Instant at) {
        this.resumedAt = at;
    }
}
