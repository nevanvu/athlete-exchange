package com.athletex.fairvalue;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.Pricing;
import com.athletex.outbox.OutboxEntity;
import com.athletex.settlement.AbstractLiveSlateTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FairValueServiceTest extends AbstractLiveSlateTest {

    @Test
    void openingTheIpoSetsEveryMarketToItsProjectionReserve() {
        Slate s = newSlate(40, 22, 9);
        assertThat(s.market(0).getFairValueCents()).isNull();

        s.toIpo();

        for (int i = 0; i < 3; i++) {
            AthleteMarketEntity market = s.market(i);
            assertThat(market.getFairValueCents())
                    .isEqualTo(Pricing.reservePriceCents(market.getProjectedFantasyPoints()));
            assertThat(tickRepository.findByMarketIdOrderByIdAsc(market.getId())).hasSize(1);
        }
        assertThat(s.market(0).getFairValueCents()).isEqualTo(1000);
    }

    @Test
    void scoringLocksInPointsAndTheClockShrinksTheRest() {
        Slate s = newSlate(40);
        s.openMarket();
        assertThat(s.market(0).getFairValueCents()).isEqualTo(1000);

        // Tip-off changes nothing: the whole game is still ahead.
        s.apply(s.clock(GameState.IN_PROGRESS, 1, 720));
        assertThat(tickRepository.findByMarketIdOrderByIdAsc(s.markets.get(0))).hasSize(1);

        EventEnvelope bucket = s.apply(s.line(0, 10, 0, 0, false));
        assertThat(s.market(0).getFairValueCents()).isEqualTo((10 + 40) * 25);
        assertThat(s.market(0).getFairValueAsOfEventId()).isEqualTo(bucket.eventId());

        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        assertThat(s.market(0).getFairValueCents()).isEqualTo((10 + 20) * 25);

        List<FairValueTickEntity> ticks = tickRepository.findByMarketIdOrderByIdAsc(s.markets.get(0));
        assertThat(ticks).extracting(FairValueTickEntity::getFairValueCents).containsExactly(1000L, 1250L, 750L);
        assertThat(ticks.get(2).getGameFractionRemaining()).isEqualByComparingTo("0.5");
        assertThat(ticks.get(2).getFpScored()).isEqualByComparingTo("10");
    }

    @Test
    void injuryCrashesFairValueAndIsPublishedWithItsCause() {
        Slate s = newSlate(40);
        s.openMarket();
        s.apply(s.line(0, 12, 0, 0, false));
        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        long before = s.market(0).getFairValueCents();
        assertThat(before).isEqualTo((12 + 20) * 25);

        EventEnvelope injury = s.apply(s.injury(0, InjuryStatus.OUT));

        assertThat(s.market(0).getFairValueCents()).isEqualTo(12 * 25);
        OutboxEntity published = outboxRepository.findByMessageKeyOrderByCreatedAtAsc(s.markets.get(0).toString())
                .getLast();
        assertThat(published.getPayload())
                .contains("\"type\":\"fair_value_updated\"")
                .contains("\"fairValueCents\":300")
                .contains("\"previousFairValueCents\":" + before)
                .contains("\"asOfEventId\":\"" + injury.eventId() + "\"");
    }

    @Test
    void newsNudgesFairValueAndTheNudgeDecays() {
        Slate s = newSlate(40);
        s.openMarket();
        EventEnvelope news = s.apply(s.news(0, 0.10, 1.0));
        assertThat(s.market(0).getFairValueCents()).isEqualTo(1100);

        AthleteMarketEntity market = s.market(0);
        FairValueModel.Result later = fairValueService.recompute(market,
                news.occurredAt().plus(Duration.ofMinutes(30)), null);
        assertThat(later.newsFactor()).isCloseTo(1 + 0.10 * Math.exp(-1), org.assertj.core.api.Assertions.within(1e-6));
        assertThat(later.fairValueCents()).isEqualTo(Math.round(1000 * (1 + 0.10 * Math.exp(-1))));
    }

    @Test
    void finishedGameFairValueEqualsWhatSettlementWillPay() {
        Slate s = newSlate(40);
        s.openMarket();
        s.apply(s.line(0, 31, 7, 5, true));   // 31 + 8.4 + 7.5 = 46.9
        s.apply(s.clock(GameState.FINAL, 4, 0));
        assertThat(s.market(0).getFairValueCents()).isEqualTo(Pricing.settlementValueCents(new BigDecimal("46.90")));
    }
}
