package com.athletex.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "games")
public class GameEntity {

    @Id
    private UUID id;

    @Column(name = "slate_id", nullable = false)
    private UUID slateId;

    @Column(name = "home_team_id", nullable = false)
    private UUID homeTeamId;

    @Column(name = "away_team_id", nullable = false)
    private UUID awayTeamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private int period;

    @Column(name = "clock_seconds_remaining", nullable = false)
    private int clockSecondsRemaining;

    /** Sequence of the game_status event that last set status/clock; older ones are ignored. */
    @Column(name = "status_sequence", nullable = false)
    private long statusSequence;

    protected GameEntity() {
    }

    public GameEntity(UUID slateId, UUID homeTeamId, UUID awayTeamId, Instant startsAt) {
        this.id = UUID.randomUUID();
        this.slateId = slateId;
        this.homeTeamId = homeTeamId;
        this.awayTeamId = awayTeamId;
        this.status = GameStatus.SCHEDULED;
        this.startsAt = startsAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public UUID getHomeTeamId() {
        return homeTeamId;
    }

    public UUID getAwayTeamId() {
        return awayTeamId;
    }

    public GameStatus getStatus() {
        return status;
    }

    void setStatus(GameStatus status) {
        this.status = status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getPeriod() {
        return period;
    }

    public int getClockSecondsRemaining() {
        return clockSecondsRemaining;
    }

    public long getStatusSequence() {
        return statusSequence;
    }

    public void updateClock(int period, int clockSecondsRemaining, long statusSequence) {
        this.period = period;
        this.clockSecondsRemaining = clockSecondsRemaining;
        this.statusSequence = statusSequence;
    }
}
