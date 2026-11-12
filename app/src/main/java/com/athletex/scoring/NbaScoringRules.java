package com.athletex.scoring;

import com.athletex.events.NbaFantasyScoring;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The published NBA formula; lives in the events module so the simulator scores identically. */
@Component
public class NbaScoringRules implements ScoringRules {

    @Override
    public String sport() {
        return "NBA";
    }

    @Override
    public double fantasyPoints(Map<String, Integer> stats) {
        return NbaFantasyScoring.fantasyPoints(stats);
    }
}
