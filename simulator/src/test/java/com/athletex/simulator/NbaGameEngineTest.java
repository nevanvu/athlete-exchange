package com.athletex.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.EventType;
import com.athletex.events.NbaFantasyScoring;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbaGameEngineTest {

    private static final Instant START = Instant.parse("2026-11-11T00:00:00Z");

    private static GameSpec spec(long seed, double injuriesPerGame) {
        return new GameSpec("game-1", "slate-1", START, seed, injuriesPerGame, List.of(
                new GameSpec.AthleteSpec("star", 45.0, 36.0),
                new GameSpec.AthleteSpec("starter", 28.0, 30.0),
                new GameSpec.AthleteSpec("bench", 12.0, 16.0)));
    }

    @Test
    void sameSpecProducesByteIdenticalEventStream() {
        EventCodec codec = new EventCodec();
        List<EventEnvelope> a = new NbaGameEngine(spec(7, 0)).runToEnd();
        List<EventEnvelope> b = new NbaGameEngine(spec(7, 0)).runToEnd();
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(codec.encode(a.get(i))).isEqualTo(codec.encode(b.get(i)));
        }
        List<EventEnvelope> c = new NbaGameEngine(spec(8, 0)).runToEnd();
        assertThat(codec.encode(c.get(c.size() - 1))).isNotEqualTo(codec.encode(a.get(a.size() - 1)));
    }

    @Test
    void sequencesAreStrictlyIncreasingAndTimestampsNeverGoBackwards() {
        List<EventEnvelope> events = new NbaGameEngine(spec(1, 0)).runToEnd();
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).sequence()).isEqualTo(events.get(i - 1).sequence() + 1);
            assertThat(events.get(i).occurredAt()).isAfterOrEqualTo(events.get(i - 1).occurredAt());
        }
        assertThat(events.get(0).type()).isEqualTo(EventType.GAME_STATUS);
        assertThat(events.get(events.size() - 1).occurredAt()).isEqualTo(START.plusSeconds(NbaGameEngine.GAME_SECONDS));
    }

    @Test
    void gameWalksThroughPeriodsHalftimeAndFinal() {
        List<EventPayload.GameStatus> statuses = new NbaGameEngine(spec(1, 0)).runToEnd().stream()
                .filter(e -> e.payload() instanceof EventPayload.GameStatus)
                .map(e -> (EventPayload.GameStatus) e.payload())
                .toList();
        assertThat(statuses.get(0)).isEqualTo(new EventPayload.GameStatus(EventPayload.GameState.IN_PROGRESS, 1, 720));
        assertThat(statuses).filteredOn(s -> s.status() == EventPayload.GameState.HALFTIME).hasSize(1);
        assertThat(statuses.get(statuses.size() - 1).status()).isEqualTo(EventPayload.GameState.FINAL);
        assertThat(statuses.stream().map(EventPayload.GameStatus::period).distinct()).containsExactly(1, 2, 3, 4);
        assertThat(statuses).hasSize(1 + NbaGameEngine.GAME_SECONDS / NbaGameEngine.TICK_SECONDS);
    }

    @Test
    void statLinesAreCumulativeAndEveryAthleteGetsExactlyOneFinalLine() {
        List<EventEnvelope> events = new NbaGameEngine(spec(3, 0)).runToEnd();
        Map<String, Map<String, Integer>> last = new HashMap<>();
        Map<String, Integer> finals = new HashMap<>();
        for (EventEnvelope e : events) {
            if (e.payload() instanceof EventPayload.StatLine line) {
                Map<String, Integer> previous = last.get(line.athleteId());
                if (previous != null) {
                    for (String key : NbaFantasyScoring.STAT_KEYS) {
                        assertThat(line.stats().get(key)).isGreaterThanOrEqualTo(previous.get(key));
                    }
                }
                last.put(line.athleteId(), line.stats());
                if (line.isFinal()) {
                    finals.merge(line.athleteId(), 1, Integer::sum);
                }
            }
        }
        assertThat(finals).containsOnlyKeys("star", "starter", "bench");
        assertThat(finals.values()).containsOnly(1);
        // The box score comes in just before the final whistle, which ends the stream.
        assertThat(events.get(events.size() - 1).payload())
                .isEqualTo(new EventPayload.GameStatus(EventPayload.GameState.FINAL, 4, 0));
        List<EventEnvelope> boxScore = events.subList(events.size() - 4, events.size() - 1);
        assertThat(boxScore).allSatisfy(e -> assertThat(((EventPayload.StatLine) e.payload()).isFinal()).isTrue());
    }

    @Test
    void fantasyPointsAverageNearTheProjectionAcrossManyGames() {
        Map<String, Double> total = new HashMap<>();
        int games = 200;
        for (int seed = 0; seed < games; seed++) {
            for (EventEnvelope e : new NbaGameEngine(spec(seed, 0)).runToEnd()) {
                if (e.payload() instanceof EventPayload.StatLine line && line.isFinal()) {
                    total.merge(line.athleteId(), NbaFantasyScoring.fantasyPoints(line.stats()), Double::sum);
                }
            }
        }
        assertThat(total.get("star") / games).isBetween(45.0 * 0.85, 45.0 * 1.15);
        assertThat(total.get("starter") / games).isBetween(28.0 * 0.85, 28.0 * 1.15);
        assertThat(total.get("bench") / games).isBetween(12.0 * 0.8, 12.0 * 1.2);
    }

    @Test
    void injectedInjuryTakesTheAthleteOffTheCourt() {
        NbaGameEngine engine = new NbaGameEngine(spec(5, 0));
        for (int i = 0; i < 20; i++) {
            engine.tick();
        }
        EventEnvelope injury = engine.inject(new EventPayload.Injury("star", EventPayload.InjuryStatus.OUT, "ankle", false));
        assertThat(injury.sequence()).isEqualTo(engine.lastSequence());
        Map<String, Integer> frozen = null;
        for (EventEnvelope e : engine.runToEnd()) {
            if (e.payload() instanceof EventPayload.StatLine line && line.athleteId().equals("star")) {
                assertThat(line.isFinal()).as("no live stat lines for an injured athlete").isTrue();
                frozen = line.stats();
            }
        }
        assertThat(frozen).isNotNull();
    }

    @Test
    void randomInjuriesHappenWhenConfigured() {
        long injuries = new NbaGameEngine(spec(9, 4.0)).runToEnd().stream()
                .filter(e -> e.type() == EventType.INJURY_EVENT).count();
        assertThat(injuries).isBetween(1L, 3L);
    }
}
