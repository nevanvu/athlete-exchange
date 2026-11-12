package com.athletex.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "athlete_game_stats")
public class AthleteGameStatsEntity {

    @EmbeddedId
    private GameAthleteKey key;

    /** Cumulative stat line as JSON ({"pts":18,"reb":5,...}). */
    @Column(nullable = false)
    private String stats;

    @Column(name = "minutes_played", nullable = false)
    private BigDecimal minutesPlayed;

    @Column(name = "fantasy_points", nullable = false)
    private BigDecimal fantasyPoints;

    @Column(name = "is_final", nullable = false)
    private boolean isFinal;

    @Column(name = "as_of_sequence", nullable = false)
    private long asOfSequence;

    @Column(name = "as_of_event_id", nullable = false)
    private String asOfEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AthleteGameStatsEntity() {
    }

    public AthleteGameStatsEntity(GameAthleteKey key) {
        this.key = key;
        this.stats = "{}";
        this.minutesPlayed = BigDecimal.ZERO;
        this.fantasyPoints = BigDecimal.ZERO;
        this.asOfSequence = -1;
        this.asOfEventId = "";
        this.updatedAt = Instant.now();
    }

    public GameAthleteKey getKey() {
        return key;
    }

    public String getStats() {
        return stats;
    }

    public BigDecimal getMinutesPlayed() {
        return minutesPlayed;
    }

    public BigDecimal getFantasyPoints() {
        return fantasyPoints;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public long getAsOfSequence() {
        return asOfSequence;
    }

    public String getAsOfEventId() {
        return asOfEventId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void replace(String statsJson, BigDecimal minutesPlayed, BigDecimal fantasyPoints, boolean isFinal,
                 long sequence, String eventId) {
        this.stats = statsJson;
        this.minutesPlayed = minutesPlayed;
        this.fantasyPoints = fantasyPoints;
        this.isFinal = isFinal;
        this.asOfSequence = sequence;
        this.asOfEventId = eventId;
        this.updatedAt = Instant.now();
    }
}
