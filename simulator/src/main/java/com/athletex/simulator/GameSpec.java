package com.athletex.simulator;

import java.time.Instant;
import java.util.List;

/** Everything the engine needs to simulate one game reproducibly. */
public record GameSpec(String gameId, String slateId, Instant startAt, long seed,
                       double injuriesPerGame, List<AthleteSpec> athletes) {

    public record AthleteSpec(String athleteId, double projectedFantasyPoints, double projectedMinutes) {
    }
}
