package com.athletex.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventPayload.GameState;
import com.athletex.market.SlateStatus;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.trading.PositionEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SettlementTest extends AbstractLiveSlateTest {

    private static final int STAR = 0;
    private static final int ROLE = 1;
    private static final int DNP = 2;

    @Test
    void fullSlatePaysEveryHolderRetiresSharesAndReconciles() {
        Slate s = newSlate(40, 20, 12); // reserves $10.00, $5.00, $3.00
        UUID alice = newTrader();
        UUID bob = newTrader();
        UUID carol = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(STAR), 1200, 600);
        ipoService.bid(bob, s.markets.get(STAR), 1100, 400);   // marginal bid: STAR clears at $11.00
        ipoService.bid(bob, s.markets.get(ROLE), 600, 300);    // undersubscribed: clears at the $5.00 reserve
        ipoService.bid(alice, s.markets.get(DNP), 400, 100);   // undersubscribed: clears at the $3.00 reserve
        s.openMarket();
        assertThat(s.market(STAR).getIpoClearingPriceCents()).isEqualTo(1100);

        orderService.place(alice, s.markets.get(STAR), Side.SELL, OrderType.LIMIT, 1300L, 100);
        orderService.place(carol, s.markets.get(STAR), Side.BUY, OrderType.LIMIT, 1300L, 100);
        orderService.place(carol, s.markets.get(ROLE), Side.BUY, OrderType.LIMIT, 450L, 10); // rests until close
        assertThat(cash(alice)).isEqualTo(SEED_CENTS - 600 * 1100 - 100 * 300 + 100 * 1300);
        long poolBefore = poolBalance();

        s.apply(s.clock(GameState.IN_PROGRESS, 1, 720));
        s.apply(s.line(STAR, 30, 8, 6, true));  // 30 + 9.6 + 9 = 48.6 -> $12.15
        s.apply(s.line(ROLE, 12, 5, 2, true));  // 12 + 6 + 3 = 21.0 -> $5.25
        s.apply(s.clock(GameState.FINAL, 4, 0)); // DNP never played: 0 points -> $1.00 floor
        assertThat(s.status()).isEqualTo(SlateStatus.CLOSED);
        assertThat(cash(carol)).as("resting bid cancelled on close").isEqualTo(SEED_CENTS - 100 * 1300);

        SettlementService.Result result = settlementService.settle(s.slateId);
        assertThat(result.outcome()).isEqualTo(SettlementService.Outcome.SETTLED);
        assertThat(s.status()).isEqualTo(SlateStatus.SETTLED);

        assertThat(s.market(STAR).getSettlementValueCents()).isEqualTo(1215);
        assertThat(s.market(ROLE).getSettlementValueCents()).isEqualTo(525);
        assertThat(s.market(DNP).getSettlementValueCents()).isEqualTo(100);
        assertThat(s.market(STAR).getFairValueCents()).isEqualTo(1215);

        long alicePaid = 500 * 1215 + 100 * 100;
        long bobPaid = 400 * 1215 + 300 * 525;
        long carolPaid = 100 * 1215;
        assertThat(cash(alice)).isEqualTo(SEED_CENTS - 660_000 - 30_000 + 130_000 + alicePaid);
        assertThat(cash(bob)).isEqualTo(SEED_CENTS - 440_000 - 150_000 + bobPaid);
        assertThat(cash(carol)).isEqualTo(SEED_CENTS - 130_000 + carolPaid);
        assertThat(poolBefore - poolBalance()).isEqualTo(alicePaid + bobPaid + carolPaid);

        SettlementRunEntity run = runRepository.findBySlateId(s.slateId).orElseThrow();
        assertThat(run.getTotalPaidCents()).isEqualTo(alicePaid + bobPaid + carolPaid);
        assertThat(run.getLedgerTransactionId()).isNotNull();
        Map<UUID, Long> paidByUser = payoutRepository.findByRunId(run.getId()).stream()
                .collect(Collectors.groupingBy(SettlementPayoutEntity::getUserId,
                        Collectors.summingLong(SettlementPayoutEntity::getAmountCents)));
        assertThat(paidByUser).containsOnly(Map.entry(alice, alicePaid), Map.entry(bob, bobPaid),
                Map.entry(carol, carolPaid));

        // Every share in the slate is retired, house inventory included.
        for (UUID market : s.markets) {
            assertThat(positionRepository.findByMarketId(market)).allSatisfy(p -> assertThat(p.getQuantity()).isZero());
        }
        // Alice: +$200 selling 100 above cost, +$575 settling 500 above cost, -$200 on the DNP shares.
        long aliceRealized = positionRepository.findByUserId(alice).stream()
                .mapToLong(PositionEntity::getRealizedPnlCents).sum();
        assertThat(aliceRealized).isEqualTo(20_000 + 57_500 - 20_000);
        assertThat(allBalances()).isZero();

        // Idempotent: a second run (redelivered trigger, restart) pays nothing more.
        assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.ALREADY_SETTLED);
        assertThat(cash(alice)).isEqualTo(SEED_CENTS - 660_000 - 30_000 + 130_000 + alicePaid);
        assertThat(poolBefore - poolBalance()).isEqualTo(alicePaid + bobPaid + carolPaid);
    }

    @Test
    void waitsForTheOfficialBoxScore() {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 1000);
        s.openMarket();
        s.apply(s.line(0, 20, 0, 0, false));
        s.apply(s.clock(GameState.FINAL, 4, 0));
        assertThat(s.status()).isEqualTo(SlateStatus.CLOSED);

        SettlementService.Result notReady = settlementService.settle(s.slateId);
        assertThat(notReady.outcome()).isEqualTo(SettlementService.Outcome.NOT_READY);
        assertThat(notReady.detail()).contains("no final stat line");
        assertThat(cash(alice)).isEqualTo(SEED_CENTS - 1_000_000);

        s.apply(s.line(0, 24, 0, 0, true));
        assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.SETTLED);
        assertThat(cash(alice)).isEqualTo(1000 * 600L);
    }

    @Test
    void onlyClosedSlatesSettle() {
        Slate s = newSlate(40);
        s.openMarket();
        assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.NOT_CLOSED);
        assertThat(s.status()).isEqualTo(SlateStatus.OPEN_MARKET);
    }

    @Test
    void concurrentRunsSettleExactlyOnce() throws Exception {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 1000);
        s.openMarket();
        s.apply(s.line(0, 50, 0, 0, true));
        s.apply(s.clock(GameState.FINAL, 4, 0));
        long poolBefore = poolBalance();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Callable<SettlementService.Outcome> attempt = () -> {
            start.await();
            return settlementService.settle(s.slateId).outcome();
        };
        List<Future<SettlementService.Outcome>> futures = List.of(
                pool.submit(attempt), pool.submit(attempt), pool.submit(attempt), pool.submit(attempt));
        start.countDown();
        List<SettlementService.Outcome> outcomes = new java.util.ArrayList<>();
        for (Future<SettlementService.Outcome> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsOnlyOnce(SettlementService.Outcome.SETTLED);
        assertThat(outcomes).filteredOn(o -> o == SettlementService.Outcome.ALREADY_SETTLED).hasSize(3);
        assertThat(poolBefore - poolBalance()).isEqualTo(1000 * 1250L);
        assertThat(cash(alice)).isEqualTo(1000 * 1250L);
    }
}
