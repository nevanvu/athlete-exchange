package com.athletex.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class GameAthleteKey implements Serializable {

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "athlete_id", nullable = false)
    private UUID athleteId;

    protected GameAthleteKey() {
    }

    public GameAthleteKey(UUID gameId, UUID athleteId) {
        this.gameId = gameId;
        this.athleteId = athleteId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public UUID getAthleteId() {
        return athleteId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GameAthleteKey k && gameId.equals(k.gameId) && athleteId.equals(k.athleteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gameId, athleteId);
    }
}
