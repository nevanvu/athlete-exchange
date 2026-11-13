package com.athletex.simulator;

import java.time.Instant;
import java.util.List;

/**
 * Everything the engine needs to simulate one game reproducibly. {@code speed} maps game time to
 * event timestamps: a game played at 20x stamps its events 1/20th of a game-second apart, so
 * occurred_at matches the wall clock the exchange and its clients run on. Recordings use 1x.
 */
public record GameSpec(String gameId, String slateId, Instant startAt, long seed,
                       double injuriesPerGame, List<AthleteSpec> athletes, double speed) {

    public GameSpec {
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
    }

    /** A game timed at real speed (recordings, tests). */
    public GameSpec(String gameId, String slateId, Instant startAt, long seed,
                    double injuriesPerGame, List<AthleteSpec> athletes) {
        this(gameId, slateId, startAt, seed, injuriesPerGame, athletes, 1.0);
    }

    public Instant timestampAt(int elapsedGameSeconds) {
        return startAt.plusMillis(Math.round(elapsedGameSeconds * 1000.0 / speed));
    }

    public record AthleteSpec(String athleteId, double projectedFantasyPoints, double projectedMinutes) {
    }
}
