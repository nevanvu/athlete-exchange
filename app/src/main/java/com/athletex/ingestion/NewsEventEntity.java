package com.athletex.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_events")
public class NewsEventEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    @Column(nullable = false)
    private String headline;

    @Column(nullable = false)
    private BigDecimal impact;

    @Column(nullable = false)
    private BigDecimal confidence;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected NewsEventEntity() {
    }

    public NewsEventEntity(String eventId, UUID gameId, UUID athleteId, String headline,
                           BigDecimal impact, BigDecimal confidence, Instant occurredAt) {
        this.eventId = eventId;
        this.gameId = gameId;
        this.athleteId = athleteId;
        this.headline = headline;
        this.impact = impact;
        this.confidence = confidence;
        this.occurredAt = occurredAt;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public UUID getAthleteId() {
        return athleteId;
    }

    public String getHeadline() {
        return headline;
    }

    public BigDecimal getImpact() {
        return impact;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
