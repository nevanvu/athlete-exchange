package com.athletex.halts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.market.MarketStatus;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.outbox.OutboxEntity;
import com.athletex.settlement.AbstractLiveSlateTest;
import com.athletex.trading.OrderStatus;
import com.athletex.trading.PlacedOrder;
import com.athletex.trading.TradeEntity;
import com.athletex.trading.TradeRepository;
import com.athletex.trading.TradingRejectedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HaltTest extends AbstractLiveSlateTest {

    @Autowired private HaltResumer resumer;
    @Autowired private MarketHaltRepository haltRepository;
    @Autowired private TradeRepository tradeRepository;

    /** Alice owns all 1000 shares; the star has 12 points at halftime, so fair value is (12 + 20) x $0.25 = $8.00. */
    private record Halftime(Slate slate, UUID alice, UUID market) {
    }

    private Halftime openAtHalftime() {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 1000);
        // Pre-game and first-half events arrive while the IPO runs: they reprice but cannot halt.
        s.apply(s.line(0, 12, 0, 0, false));
        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        s.openMarket();
        assertThat(s.market(0).getFairValueCents()).isEqualTo(800);
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.ACTIVE);
        return new Halftime(s, alice, s.markets.get(0));
    }

    @Test
    void injuryHaltsTheMarketBeforeTheNewPriceIsPublished() {
        Halftime h = openAtHalftime();
        PlacedOrder resting = orderService.place(h.alice(), h.market(), Side.SELL, OrderType.LIMIT, 900L, 10);
        UUID bob = newTrader();
        Instant before = Instant.now();

        EventEnvelope injury = h.slate().apply(h.slate().injury(0, InjuryStatus.OUT));

        var market = h.slate().market(0);
        assertThat(market.getStatus()).isEqualTo(MarketStatus.HALTED);
        assertThat(market.getHaltReason()).contains("ruled OUT");
        assertThat(market.getFairValueCents()).isEqualTo(300);
        assertThat(market.getResumeAfter()).isBetween(before.plusSeconds(29), Instant.now().plusSeconds(31));

        List<MarketHaltEntity> halts = haltRepository.findByMarketIdOrderByHaltedAtAsc(h.market());
        assertThat(halts).singleElement().satisfies(halt -> {
            assertThat(halt.getTriggerEventId()).isEqualTo(injury.eventId());
            assertThat(halt.getFairValueBeforeCents()).isEqualTo(800);
            assertThat(halt.getFairValueAfterCents()).isEqualTo(300);
        });

        // Consumers learn about the halt before they see the price that caused it.
        List<String> published = outboxRepository.findByMessageKeyOrderBySeqAsc(h.market().toString()).stream()
                .map(OutboxEntity::getPayload).toList();
        int haltIndex = indexOf(published, "\"type\":\"market_halted\"");
        int priceIndex = indexOf(published, "\"fairValueCents\":300");
        assertThat(haltIndex).isNotNegative().isLessThan(priceIndex);

        assertThatThrownBy(() -> orderService.place(bob, h.market(), Side.BUY, OrderType.LIMIT, 900L, 10))
                .isInstanceOf(TradingRejectedException.class)
                .hasMessageContaining("halted");
        // Pulling a resting order is always allowed.
        assertThat(orderService.cancel(h.alice(), resting.order().getId()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void haltLiftsOnlyAfterTheMinimumDuration() {
        Halftime h = openAtHalftime();
        h.slate().apply(h.slate().injury(0, InjuryStatus.OUT));
        Instant resumeAfter = h.slate().market(0).getResumeAfter();

        // resumeDue sweeps every market in the database, so assert on this market, not on counts.
        resumer.resumeDue(resumeAfter.minusSeconds(1));
        assertThat(h.slate().market(0).getStatus()).isEqualTo(MarketStatus.HALTED);

        resumer.resumeDue(resumeAfter);
        var market = h.slate().market(0);
        assertThat(market.getStatus()).isEqualTo(MarketStatus.ACTIVE);
        assertThat(market.getHaltReason()).isNull();
        assertThat(haltRepository.findByMarketIdOrderByHaltedAtAsc(h.market()).getFirst().getResumedAt()).isNotNull();
        assertThat(outboxRepository.findByMessageKeyOrderBySeqAsc(h.market().toString()).getLast().getPayload())
                .contains("\"type\":\"market_resumed\"");

        PlacedOrder order = orderService.place(h.alice(), h.market(), Side.SELL, OrderType.LIMIT, 400L, 10);
        assertThat(order.order().getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void largeSingleEventMovesHaltButSmallOrCheapOnesDoNot() {
        Slate s = newSlate(40, 8);                 // fair values $10.00 and $2.00
        s.openMarket();

        s.apply(s.line(0, 2, 0, 0, false));        // $10.00 -> $10.50: 5%
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.ACTIVE);

        s.apply(s.line(1, 3, 0, 0, false));        // $2.00 -> $2.75: 37%, but only $0.75
        assertThat(s.market(1).getFairValueCents()).isEqualTo(275);
        assertThat(s.market(1).getStatus()).isEqualTo(MarketStatus.ACTIVE);

        s.apply(s.line(0, 9, 0, 0, false));        // $10.50 -> $12.25: 17% and $1.75
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.HALTED);
        assertThat(s.market(0).getHaltReason()).isEqualTo("fair value moved 17% on one event");
    }

    @Test
    void marketsDoNotHaltBeforeTradingStarts() {
        Slate s = newSlate(40);
        s.toIpo();
        s.apply(s.injury(0, InjuryStatus.OUT));
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.ACTIVE);
        assertThat(haltRepository.findByMarketIdOrderByHaltedAtAsc(s.markets.get(0))).isEmpty();
    }

    @Test
    void aSecondShockDuringAHaltExtendsIt() throws InterruptedException {
        Halftime h = openAtHalftime();
        h.slate().apply(h.slate().injury(0, InjuryStatus.OUT));            // $8.00 -> $3.00
        Instant firstResume = h.slate().market(0).getResumeAfter();
        Thread.sleep(20);
        h.slate().apply(h.slate().line(0, 20, 0, 0, true));               // stat correction: $3.00 -> $5.00

        assertThat(h.slate().market(0).getResumeAfter()).isAfter(firstResume);
        assertThat(haltRepository.findByMarketIdOrderByHaltedAtAsc(h.market())).hasSize(2);
        resumer.resumeDue(firstResume);
        assertThat(h.slate().market(0).getStatus()).isEqualTo(MarketStatus.HALTED);
    }

    @Test
    void manualHaltsAreNeverLiftedAutomatically() {
        Halftime h = openAtHalftime();
        lifecycle.haltMarket(h.market(), "integrity review");
        h.slate().apply(h.slate().injury(0, InjuryStatus.OUT));

        var market = h.slate().market(0);
        assertThat(market.getStatus()).isEqualTo(MarketStatus.HALTED);
        assertThat(market.getResumeAfter()).isNull();
        resumer.resumeDue(Instant.now().plus(Duration.ofHours(1)));
        assertThat(h.slate().market(0).getStatus()).isEqualTo(MarketStatus.HALTED);
    }

    @Test
    void anOrderRacingAnInjuryNeverFillsAfterTheHalt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int filled = 0;
        int rejected = 0;
        for (int round = 0; round < 8; round++) {
            Halftime h = openAtHalftime();
            UUID bob = newTrader();
            orderService.place(h.alice(), h.market(), Side.SELL, OrderType.LIMIT, 850L, 10);
            EventEnvelope injury = h.slate().next(h.slate().injury(0, InjuryStatus.OUT));

            CountDownLatch start = new CountDownLatch(1);
            Future<?> news = pool.submit(() -> {
                start.await();
                return ingestion.ingest(injury);
            });
            Future<Boolean> order = pool.submit(() -> {
                start.await();
                try {
                    orderService.place(bob, h.market(), Side.BUY, OrderType.LIMIT, 850L, 10);
                    return true;
                } catch (TradingRejectedException e) {
                    return false;
                }
            });
            start.countDown();
            news.get();
            boolean accepted = order.get();

            MarketHaltEntity halt = haltRepository.findByMarketIdOrderByHaltedAtAsc(h.market()).getFirst();
            List<TradeEntity> trades = tradeRepository.findTop50ByMarketIdOrderByExecutedAtDesc(h.market());
            if (accepted) {
                assertThat(trades).singleElement()
                        .satisfies(t -> assertThat(t.getExecutedAt()).isBefore(halt.getHaltedAt()));
                filled++;
            } else {
                assertThat(trades).isEmpty();
                rejected++;
            }
        }
        pool.shutdown();
        assertThat(filled + rejected).isEqualTo(8);
    }

    private static int indexOf(List<String> payloads, String fragment) {
        for (int i = 0; i < payloads.size(); i++) {
            if (payloads.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
