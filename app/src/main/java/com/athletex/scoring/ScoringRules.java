package com.athletex.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Converts a raw cumulative stat line into fantasy points. One implementation per sport. */
public interface ScoringRules {

    String sport();

    double fantasyPoints(Map<String, Integer> stats);

    default BigDecimal fantasyPointsDecimal(Map<String, Integer> stats) {
        return BigDecimal.valueOf(fantasyPoints(stats)).setScale(2, RoundingMode.HALF_UP);
    }
}
