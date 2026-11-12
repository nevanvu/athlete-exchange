package com.athletex.simulator;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.events.NbaFantasyScoring;
import com.athletex.events.Ulid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Steps a basketball game forward in {@value #TICK_SECONDS}-second ticks, emitting the event
 * stream from docs/event-schema.md. Fully deterministic for a given {@link GameSpec}: same spec,
 * same events, same ids — which is what makes recorded games replayable in tests.
 *
 * <p>Scoring is stochastic around each athlete's projection: stat counts per tick are Poisson
 * draws whose rates sum to the projected fantasy points over the projected minutes.
 */
public final class NbaGameEngine {

    public static final int TICK_SECONDS = 30;
    public static final int PERIOD_SECONDS = 720;
    public static final int PERIODS = 4;
    public static final int GAME_SECONDS = PERIOD_SECONDS * PERIODS;
    public static final String SOURCE = "simulator";

    /** Relative contribution of each stat to an athlete's fantasy points (turnovers subtract). */
    private static final double PTS_WEIGHT = 0.61, REB_WEIGHT = 0.167, AST_WEIGHT = 0.111,
            STL_WEIGHT = 0.0167, BLK_WEIGHT = 0.0133, TOV_WEIGHT = 0.0667;

    /** Scales the weights so positive contributions minus turnovers net to exactly the projection. */
    private static final double NORMALIZER =
            1.0 / (PTS_WEIGHT + REB_WEIGHT + AST_WEIGHT + STL_WEIGHT + BLK_WEIGHT - TOV_WEIGHT);

    private static final double PTS_SHARE = PTS_WEIGHT * NORMALIZER, REB_SHARE = REB_WEIGHT * NORMALIZER,
            AST_SHARE = AST_WEIGHT * NORMALIZER, STL_SHARE = STL_WEIGHT * NORMALIZER,
            BLK_SHARE = BLK_WEIGHT * NORMALIZER, TOV_SHARE = TOV_WEIGHT * NORMALIZER;

    private final class AthleteState {
        final GameSpec.AthleteSpec spec;
        final Map<String, Integer> stats = new LinkedHashMap<>();
        double minutesPlayed;
        boolean active = true;
        final double onCourtProbability;
        final double ticksOnCourt;

        AthleteState(GameSpec.AthleteSpec spec) {
            this.spec = spec;
            NbaFantasyScoring.STAT_KEYS.forEach(k -> stats.put(k, 0));
            this.onCourtProbability = Math.min(1.0, spec.projectedMinutes() / 48.0);
            this.ticksOnCourt = Math.max(1.0, spec.projectedMinutes() * 60.0 / TICK_SECONDS);
        }

        double perTick(double share, double perUnit) {
            return spec.projectedFantasyPoints() * share / perUnit / ticksOnCourt;
        }
    }

    private final GameSpec spec;
    private final Random random;
    private final Map<String, AthleteState> athletes = new LinkedHashMap<>();
    private long sequence = 0;
    private int elapsedSeconds = 0;
    private boolean started = false;
    private boolean finished = false;

    public NbaGameEngine(GameSpec spec) {
        this.spec = spec;
        this.random = new Random(spec.seed());
        for (GameSpec.AthleteSpec a : spec.athletes()) {
            athletes.put(a.athleteId(), new AthleteState(a));
        }
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public int elapsedSeconds() {
        return elapsedSeconds;
    }

    public long lastSequence() {
        return sequence;
    }

    /** Advances one tick and returns the events it produced (empty once the game is over). */
    public synchronized List<EventEnvelope> tick() {
        if (finished) {
            return List.of();
        }
        List<EventEnvelope> out = new ArrayList<>();
        if (!started) {
            started = true;
            out.add(emit(new EventPayload.GameStatus(GameState.IN_PROGRESS, 1, PERIOD_SECONDS)));
            return out;
        }

        elapsedSeconds += TICK_SECONDS;
        int period = Math.min(PERIODS, (elapsedSeconds - 1) / PERIOD_SECONDS + 1);
        int clock = PERIOD_SECONDS - ((elapsedSeconds - 1) % PERIOD_SECONDS + 1);

        if (elapsedSeconds >= GAME_SECONDS) {
            out.add(emit(new EventPayload.GameStatus(GameState.FINAL, PERIODS, 0)));
            finished = true;
        } else if (elapsedSeconds == 2 * PERIOD_SECONDS) {
            out.add(emit(new EventPayload.GameStatus(GameState.HALFTIME, 2, 0)));
        } else {
            out.add(emit(new EventPayload.GameStatus(GameState.IN_PROGRESS, period, clock)));
        }

        maybeRandomInjury(out);

        for (AthleteState a : athletes.values()) {
            if (finished) {
                out.add(emit(new EventPayload.StatLine(a.spec.athleteId(), Map.copyOf(a.stats), a.minutesPlayed, true)));
            } else if (a.active && random.nextDouble() < a.onCourtProbability) {
                a.minutesPlayed += TICK_SECONDS / 60.0;
                if (play(a)) {
                    out.add(emit(new EventPayload.StatLine(a.spec.athleteId(), Map.copyOf(a.stats), a.minutesPlayed, false)));
                }
            }
        }
        return out;
    }

    /** Emits an out-of-band event (injury, news) with the next sequence number. */
    public synchronized EventEnvelope inject(EventPayload payload) {
        if (payload instanceof EventPayload.Injury injury) {
            AthleteState a = athletes.get(injury.athleteId());
            if (a != null) {
                a.active = injury.status() != InjuryStatus.OUT;
            }
        }
        return emit(payload);
    }

    public synchronized List<EventEnvelope> runToEnd() {
        List<EventEnvelope> all = new ArrayList<>();
        while (!finished) {
            all.addAll(tick());
        }
        return all;
    }

    private boolean play(AthleteState a) {
        int before = a.stats.hashCode();
        add(a, "pts", 2 * poisson(a.perTick(PTS_SHARE * 0.60, 2.0)));
        add(a, "pts", 3 * poisson(a.perTick(PTS_SHARE * 0.25, 3.0)));
        add(a, "pts", poisson(a.perTick(PTS_SHARE * 0.15, 1.0)));
        add(a, "reb", poisson(a.perTick(REB_SHARE, 1.2)));
        add(a, "ast", poisson(a.perTick(AST_SHARE, 1.5)));
        add(a, "stl", poisson(a.perTick(STL_SHARE, 3.0)));
        add(a, "blk", poisson(a.perTick(BLK_SHARE, 3.0)));
        add(a, "tov", poisson(a.perTick(TOV_SHARE, 1.0)));
        return a.stats.hashCode() != before;
    }

    private static void add(AthleteState a, String key, int delta) {
        if (delta != 0) {
            a.stats.merge(key, delta, Integer::sum);
        }
    }

    private void maybeRandomInjury(List<EventEnvelope> out) {
        if (spec.injuriesPerGame() <= 0 || finished) {
            return;
        }
        double perTick = spec.injuriesPerGame() / (double) (GAME_SECONDS / TICK_SECONDS);
        if (random.nextDouble() >= perTick) {
            return;
        }
        List<AthleteState> candidates = athletes.values().stream().filter(s -> s.active).toList();
        if (candidates.isEmpty()) {
            return;
        }
        AthleteState victim = candidates.get(random.nextInt(candidates.size()));
        victim.active = false;
        out.add(emit(new EventPayload.Injury(victim.spec.athleteId(), InjuryStatus.OUT, "simulated injury", false)));
    }

    private int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        double limit = Math.exp(-lambda);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    private EventEnvelope emit(EventPayload payload) {
        sequence++;
        Instant at = spec.startAt().plusSeconds(elapsedSeconds);
        String id = Ulid.generate(at.toEpochMilli(), random);
        return EventEnvelope.create(id, SOURCE, spec.gameId(), spec.slateId(), sequence, at, payload);
    }
}
