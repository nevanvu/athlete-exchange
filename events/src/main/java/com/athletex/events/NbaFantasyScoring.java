package com.athletex.events;

import java.util.List;
import java.util.Map;

/** Published NBA fantasy formula from docs/fair-value-model.md. Part of the wire contract. */
public final class NbaFantasyScoring {

    public static final List<String> STAT_KEYS = List.of("pts", "reb", "ast", "stl", "blk", "tov");

    private NbaFantasyScoring() {
    }

    public static double fantasyPoints(Map<String, Integer> stats) {
        return stat(stats, "pts") * 1.0
                + stat(stats, "reb") * 1.2
                + stat(stats, "ast") * 1.5
                + stat(stats, "stl") * 3.0
                + stat(stats, "blk") * 3.0
                - stat(stats, "tov") * 1.0;
    }

    private static int stat(Map<String, Integer> stats, String key) {
        Integer value = stats.get(key);
        return value == null ? 0 : value;
    }
}
