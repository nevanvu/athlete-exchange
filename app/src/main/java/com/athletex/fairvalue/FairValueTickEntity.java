package com.athletex.fairvalue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fair_value_ticks")
public class FairValueTickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "fair_value_cents", nullable = false)
    private long fairValueCents;

    @Column(name = "fp_scored", nullable = false)
    private BigDecimal fpScored;

    @Column(name = "fp_remaining", nullable = false)
    private BigDecimal fpRemaining;

    @Column(nullable = false)
    private BigDecimal availability;

    @Column(name = "news_factor", nullable = false)
    private BigDecimal newsFactor;

    @Column(name = "game_fraction_remaining", nullable = false)
    private BigDecimal gameFractionRemaining;

    @Column(name = "as_of_event_id")
    private String asOfEventId;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected FairValueTickEntity() {
    }

    public FairValueTickEntity(UUID marketId, FairValueModel.Result result, String asOfEventId, Instant computedAt) {
        this.marketId = marketId;
        this.fairValueCents = result.fairValueCents();
        this.fpScored = scaled(result.fantasyPointsScored(), 2);
        this.fpRemaining = scaled(result.fantasyPointsRemaining(), 2);
        this.availability = scaled(result.availability(), 2);
        this.newsFactor = scaled(result.newsFactor(), 4);
        this.gameFractionRemaining = scaled(result.gameFractionRemaining(), 4);
        this.asOfEventId = asOfEventId;
        this.computedAt = computedAt;
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public long getFairValueCents() {
        return fairValueCents;
    }

    public BigDecimal getFpScored() {
        return fpScored;
    }

    public BigDecimal getFpRemaining() {
        return fpRemaining;
    }

    public BigDecimal getAvailability() {
        return availability;
    }

    public BigDecimal getNewsFactor() {
        return newsFactor;
    }

    public BigDecimal getGameFractionRemaining() {
        return gameFractionRemaining;
    }

    public String getAsOfEventId() {
        return asOfEventId;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
