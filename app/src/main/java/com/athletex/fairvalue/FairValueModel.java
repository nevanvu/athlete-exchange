package com.athletex.fairvalue;

import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.market.GameStatus;
import com.athletex.market.Pricing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Pure fair-value math from docs/fair-value-model.md:
 *
 * <pre>
 * E[fp_final]  = fp_scored + projected_fp x fraction_of_game_remaining x availability
 * FV           = clamp( clamp(E x $0.25, $1, $100) x news_factor, $1, $100 )
 * news_factor  = clamp( 1 + sum(impact x confidence x exp(-age / tau)), 0.7, 1.3 )
 * </pre>
 *
 * The projected-minutes terms cancel (rate x minutes = projected points), so only the share of
 * game time left matters. Deterministic for given inputs: news decay is evaluated at an explicit
 * instant, which is the event's own time during ingestion, so replays reproduce exactly.
 */
public final class FairValueModel {

    public static final int GAME_SECONDS = 4 * 720;
    public static final Duration NEWS_TAU = Duration.ofMinutes(30);
    public static final double NEWS_FLOOR = 0.7;
    public static final double NEWS_CAP = 1.3;
    private static final double NEGLIGIBLE_NEWS = 0.001;

    public record News(double impact, double confidence, Instant occurredAt) {
    }

    public record Inputs(double projectedFantasyPoints, double fantasyPointsScored, GameStatus gameStatus,
                         int period, int clockSecondsRemaining, InjuryStatus availability,
                         List<News> news, Instant at) {
    }

    public record Result(long fairValueCents, double fantasyPointsScored, double fantasyPointsRemaining,
                         double availability, double newsFactor, double gameFractionRemaining) {

        public double expectedFinalFantasyPoints() {
            return fantasyPointsScored + fantasyPointsRemaining;
        }
    }

    private FairValueModel() {
    }

    public static Result compute(Inputs in) {
        double fraction = fractionRemaining(in.gameStatus(), in.period(), in.clockSecondsRemaining());
        double availability = availabilityMultiplier(in.availability());
        double remaining = in.projectedFantasyPoints() * fraction * availability;
        double expected = in.fantasyPointsScored() + remaining;
        double newsFactor = newsFactor(in.news(), in.at());

        // Same rounding as settlement, so a finished game's fair value equals its settlement value.
        long anchored = Pricing.settlementValueCents(BigDecimal.valueOf(expected));
        long fairValue = clamp(Math.round(anchored * newsFactor));
        return new Result(fairValue, in.fantasyPointsScored(), remaining, availability, newsFactor, fraction);
    }

    /** Share of regulation time still to play: 1 before tip-off, 0 once the game is over. */
    public static double fractionRemaining(GameStatus status, int period, int clockSecondsRemaining) {
        return switch (status) {
            case SCHEDULED, POSTPONED -> 1.0;
            case FINAL, CANCELLED -> 0.0;
            case IN_PROGRESS, HALFTIME -> {
                if (period <= 0) {
                    yield 1.0;
                }
                int elapsed = (period - 1) * 720 + (720 - clockSecondsRemaining);
                yield Math.max(0.0, Math.min(1.0, 1.0 - (double) elapsed / GAME_SECONDS));
            }
        };
    }

    public static double availabilityMultiplier(InjuryStatus status) {
        if (status == null) {
            return 1.0;
        }
        return switch (status) {
            case ACTIVE -> 1.0;
            case QUESTIONABLE -> 0.6;
            case DOUBTFUL -> 0.25;
            case OUT -> 0.0;
        };
    }

    /** Sentiment multiplier from news that has already happened at {@code at}, decaying with age. */
    public static double newsFactor(List<News> news, Instant at) {
        double sum = 0.0;
        for (News item : news) {
            if (item.occurredAt().isAfter(at)) {
                continue;
            }
            double ageMinutes = Duration.between(item.occurredAt(), at).toMillis() / 60_000.0;
            double contribution = item.impact() * item.confidence() * Math.exp(-ageMinutes / NEWS_TAU.toMinutes());
            if (Math.abs(contribution) >= NEGLIGIBLE_NEWS) {
                sum += contribution;
            }
        }
        return Math.max(NEWS_FLOOR, Math.min(NEWS_CAP, 1.0 + sum));
    }

    private static long clamp(long cents) {
        return Math.max(Pricing.FLOOR_CENTS, Math.min(Pricing.CAP_CENTS, cents));
    }
}
