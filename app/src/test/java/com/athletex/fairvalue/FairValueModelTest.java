package com.athletex.fairvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.market.GameStatus;
import com.athletex.market.Pricing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FairValueModelTest {

    private static final Instant T = Instant.parse("2026-11-11T20:00:00Z");

    private static FairValueModel.Result compute(double projected, double scored, GameStatus status, int period,
                                                 int clock, InjuryStatus availability, List<FairValueModel.News> news) {
        return FairValueModel.compute(new FairValueModel.Inputs(projected, scored, status, period, clock,
                availability, news, T));
    }

    @Test
    void preGameFairValueIsTheProjectionThroughTheSettlementFormula() {
        FairValueModel.Result result = compute(42, 0, GameStatus.SCHEDULED, 0, 0, InjuryStatus.ACTIVE, List.of());
        assertThat(result.fairValueCents()).isEqualTo(Pricing.reservePriceCents(BigDecimal.valueOf(42)));
        assertThat(result.fairValueCents()).isEqualTo(1050);
        assertThat(result.gameFractionRemaining()).isEqualTo(1.0);
    }

    @Test
    void scoredPointsAreLockedInAndTheRemainderShrinksWithTheClock() {
        // Halftime: half the projection is still expected on top of what has been scored.
        FairValueModel.Result half = compute(40, 18, GameStatus.HALFTIME, 2, 0, InjuryStatus.ACTIVE, List.of());
        assertThat(half.gameFractionRemaining()).isEqualTo(0.5);
        assertThat(half.expectedFinalFantasyPoints()).isEqualTo(38.0);
        assertThat(half.fairValueCents()).isEqualTo(950);

        // 3 minutes into the 4th quarter: 9/48 of the game left.
        FairValueModel.Result late = compute(40, 30, GameStatus.IN_PROGRESS, 4, 540, InjuryStatus.ACTIVE, List.of());
        assertThat(late.gameFractionRemaining()).isCloseTo(9.0 / 48.0, within(1e-9));
        assertThat(late.fairValueCents()).isEqualTo(Math.round((30 + 40 * 9.0 / 48.0) * 25));

        // 56.9 x 25 = 1422.5 exactly: a rounding tie, which must break the same way as settlement.
        FairValueModel.Result fin = compute(40, 56.9, GameStatus.FINAL, 4, 0, InjuryStatus.ACTIVE, List.of());
        assertThat(fin.fairValueCents()).isEqualTo(Pricing.settlementValueCents(new BigDecimal("56.90")));
        assertThat(fin.fairValueCents()).isEqualTo(1423);
    }

    @Test
    void injuryStatusScalesOnlyTheRemainingPoints() {
        assertThat(compute(40, 10, GameStatus.HALFTIME, 2, 0, InjuryStatus.OUT, List.of()).fairValueCents())
                .isEqualTo(250);
        assertThat(compute(40, 10, GameStatus.HALFTIME, 2, 0, InjuryStatus.QUESTIONABLE, List.of()).fairValueCents())
                .isEqualTo(Math.round((10 + 20 * 0.6) * 25));
        assertThat(compute(40, 10, GameStatus.HALFTIME, 2, 0, InjuryStatus.DOUBTFUL, List.of()).fairValueCents())
                .isEqualTo(Math.round((10 + 20 * 0.25) * 25));
    }

    @Test
    void newsDecaysExponentiallyAndIsBounded() {
        List<FairValueModel.News> fresh = List.of(new FairValueModel.News(0.10, 0.8, T));
        assertThat(FairValueModel.newsFactor(fresh, T)).isCloseTo(1.08, within(1e-9));
        assertThat(FairValueModel.newsFactor(fresh, T.plus(Duration.ofMinutes(30))))
                .isCloseTo(1 + 0.08 * Math.exp(-1), within(1e-9));
        assertThat(FairValueModel.newsFactor(fresh, T.plus(Duration.ofHours(10)))).isEqualTo(1.0);

        // News that has not happened yet at the evaluation instant is ignored (replay safety).
        assertThat(FairValueModel.newsFactor(fresh, T.minusSeconds(1))).isEqualTo(1.0);

        List<FairValueModel.News> pileOn = List.of(
                new FairValueModel.News(0.15, 1, T), new FairValueModel.News(0.15, 1, T), new FairValueModel.News(0.15, 1, T));
        assertThat(FairValueModel.newsFactor(pileOn, T)).isEqualTo(FairValueModel.NEWS_CAP);
        List<FairValueModel.News> slam = List.of(
                new FairValueModel.News(-0.15, 1, T), new FairValueModel.News(-0.15, 1, T), new FairValueModel.News(-0.15, 1, T));
        assertThat(FairValueModel.newsFactor(slam, T)).isEqualTo(FairValueModel.NEWS_FLOOR);

        FairValueModel.Result nudged = compute(40, 0, GameStatus.SCHEDULED, 0, 0, InjuryStatus.ACTIVE, fresh);
        assertThat(nudged.fairValueCents()).isEqualTo(1080);
    }

    @Test
    void fairValueStaysInsideTheSettlementRange() {
        assertThat(compute(2, 0, GameStatus.SCHEDULED, 0, 0, InjuryStatus.ACTIVE,
                List.of(new FairValueModel.News(-0.15, 1, T))).fairValueCents()).isEqualTo(Pricing.FLOOR_CENTS);
        assertThat(compute(390, 0, GameStatus.SCHEDULED, 0, 0, InjuryStatus.ACTIVE,
                List.of(new FairValueModel.News(0.15, 1, T))).fairValueCents()).isEqualTo(Pricing.CAP_CENTS);
    }

    @Test
    void gameClockMapsToFractionRemaining() {
        assertThat(FairValueModel.fractionRemaining(GameStatus.IN_PROGRESS, 1, 720)).isEqualTo(1.0);
        assertThat(FairValueModel.fractionRemaining(GameStatus.IN_PROGRESS, 2, 360)).isEqualTo(0.625);
        assertThat(FairValueModel.fractionRemaining(GameStatus.IN_PROGRESS, 4, 0)).isEqualTo(0.0);
        assertThat(FairValueModel.fractionRemaining(GameStatus.FINAL, 4, 0)).isEqualTo(0.0);
        assertThat(FairValueModel.fractionRemaining(GameStatus.CANCELLED, 2, 300)).isEqualTo(0.0);
    }
}
