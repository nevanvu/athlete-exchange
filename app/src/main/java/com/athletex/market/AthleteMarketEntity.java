package com.athletex.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Written concurrently by trading (last trade price), fair value and settlement, each touching
 * different columns in separate transactions; dynamic updates keep one writer from overwriting
 * another's columns with the stale values it loaded.
 */
@Entity
@Table(name = "athlete_markets")
@DynamicUpdate
public class AthleteMarketEntity {

    @Id
    private UUID id;

    @Column(name = "slate_id", nullable = false)
    private UUID slateId;

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MarketStatus status;

    @Column(name = "projected_fantasy_points", nullable = false)
    private BigDecimal projectedFantasyPoints;

    @Column(name = "projected_minutes", nullable = false)
    private BigDecimal projectedMinutes;

    @Column(name = "total_shares", nullable = false)
    private int totalShares;

    @Column(name = "ipo_clearing_price_cents")
    private Long ipoClearingPriceCents;

    @Column(name = "last_trade_price_cents")
    private Long lastTradePriceCents;

    @Column(name = "fair_value_cents")
    private Long fairValueCents;

    @Column(name = "fair_value_as_of_event_id")
    private String fairValueAsOfEventId;

    @Column(name = "fair_value_updated_at")
    private Instant fairValueUpdatedAt;

    @Column(name = "settlement_value_cents")
    private Long settlementValueCents;

    @Column(name = "settlement_fantasy_points")
    private BigDecimal settlementFantasyPoints;

    @Column(name = "halt_reason")
    private String haltReason;

    @Column(name = "halted_at")
    private Instant haltedAt;

    /** When an automatic halt may lift; null for manual halts, which only lift manually. */
    @Column(name = "resume_after")
    private Instant resumeAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AthleteMarketEntity() {
    }

    public AthleteMarketEntity(UUID slateId, UUID athleteId, UUID gameId,
                               BigDecimal projectedFantasyPoints, BigDecimal projectedMinutes, int totalShares) {
        this.id = UUID.randomUUID();
        this.slateId = slateId;
        this.athleteId = athleteId;
        this.gameId = gameId;
        this.status = MarketStatus.ACTIVE;
        this.projectedFantasyPoints = projectedFantasyPoints;
        this.projectedMinutes = projectedMinutes;
        this.totalShares = totalShares;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public UUID getAthleteId() {
        return athleteId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public MarketStatus getStatus() {
        return status;
    }


    public BigDecimal getProjectedFantasyPoints() {
        return projectedFantasyPoints;
    }

    public BigDecimal getProjectedMinutes() {
        return projectedMinutes;
    }

    public int getTotalShares() {
        return totalShares;
    }

    public Long getIpoClearingPriceCents() {
        return ipoClearingPriceCents;
    }

    public void setIpoClearingPriceCents(Long ipoClearingPriceCents) {
        this.ipoClearingPriceCents = ipoClearingPriceCents;
    }

    public Long getLastTradePriceCents() {
        return lastTradePriceCents;
    }

    public void setLastTradePriceCents(Long lastTradePriceCents) {
        this.lastTradePriceCents = lastTradePriceCents;
    }

    public Long getFairValueCents() {
        return fairValueCents;
    }

    public String getFairValueAsOfEventId() {
        return fairValueAsOfEventId;
    }

    public Instant getFairValueUpdatedAt() {
        return fairValueUpdatedAt;
    }

    public void updateFairValue(long fairValueCents, String asOfEventId, Instant at) {
        this.fairValueCents = fairValueCents;
        this.fairValueAsOfEventId = asOfEventId;
        this.fairValueUpdatedAt = at;
    }

    public Long getSettlementValueCents() {
        return settlementValueCents;
    }

    public BigDecimal getSettlementFantasyPoints() {
        return settlementFantasyPoints;
    }

    public void setSettlement(long valueCents, BigDecimal fantasyPoints) {
        this.settlementValueCents = valueCents;
        this.settlementFantasyPoints = fantasyPoints;
    }

    public String getHaltReason() {
        return haltReason;
    }

    public Instant getHaltedAt() {
        return haltedAt;
    }

    public Instant getResumeAfter() {
        return resumeAfter;
    }

    public void halt(String reason, Instant at, Instant resumeAfter) {
        if (this.status != MarketStatus.HALTED) {
            this.haltedAt = at;
        }
        this.status = MarketStatus.HALTED;
        this.haltReason = reason;
        this.resumeAfter = resumeAfter;
    }

    public void resume() {
        this.status = MarketStatus.ACTIVE;
        this.haltReason = null;
        this.haltedAt = null;
        this.resumeAfter = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
