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

@Entity
@Table(name = "athlete_markets")
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

    void setStatus(MarketStatus status) {
        this.status = status;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
