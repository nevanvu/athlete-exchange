package com.athletex.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.InsufficientFundsException;
import com.athletex.market.GameStatus;
import com.athletex.market.SlateStatus;
import com.athletex.matching.BookSnapshot;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingFlowTest extends AbstractTradingTest {

    private MarketFixture f;
    private UUID marketId;
    private UUID alice; // 600 shares @ 400 after IPO
    private UUID bob;   // 400 shares @ 400 after IPO
    private UUID carol; // cash only

    @BeforeEach
    void openMarketWithHoldings() {
        f = scheduledMarket(LOW_PROJECTION);
        marketId = f.market().getId();
        alice = newTrader();
        bob = newTrader();
        carol = newTrader();
        toIpo(f);
        ipoService.bid(alice, marketId, 500, 600);
        ipoService.bid(bob, marketId, 400, 600);
        openMarket(f);
        assertThat(shares(alice, marketId)).isEqualTo(600);
        assertThat(shares(bob, marketId)).isEqualTo(400);
    }

    @Test
    void takerBuyFillsAtMakerPriceAndRefundsImprovement() {
        long bobCash = cash(bob);
        long aliceCash = cash(alice);

        PlacedOrder sell = orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 100);
        assertThat(sell.trades()).isEmpty();
        assertThat(position(alice, marketId).getLockedQuantity()).isEqualTo(100);

        PlacedOrder buy = orderService.place(bob, marketId, Side.BUY, OrderType.LIMIT, 460L, 100);
        assertThat(buy.trades()).singleElement().satisfies(t -> {
            assertThat(t.getPriceCents()).isEqualTo(450);
            assertThat(t.getQuantity()).isEqualTo(100);
        });
        assertThat(buy.order().getStatus()).isEqualTo(OrderStatus.FILLED);

        assertThat(cash(bob)).isEqualTo(bobCash - 45_000);
        assertThat(escrow(bob)).isZero();
        assertThat(cash(alice)).isEqualTo(aliceCash + 45_000);

        PositionEntity alicePos = position(alice, marketId);
        assertThat(alicePos.getQuantity()).isEqualTo(500);
        assertThat(alicePos.getLockedQuantity()).isZero();
        assertThat(alicePos.getRealizedPnlCents()).isEqualTo((450 - 400) * 100);
        PositionEntity bobPos = position(bob, marketId);
        assertThat(bobPos.getQuantity()).isEqualTo(500);
        assertThat(bobPos.averageCostCents()).isEqualTo((400 * 400 + 450 * 100) / 500);

        assertThat(reload(f.market()).getLastTradePriceCents()).isEqualTo(450);
        assertThat(registry.book(marketId).snapshot().asks()).isEmpty();
    }

    @Test
    void partialFillRestsRemainderWithEscrowAndCancelReleasesIt() {
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 100);
        long bobCash = cash(bob);

        PlacedOrder buy = orderService.place(bob, marketId, Side.BUY, OrderType.LIMIT, 450L, 150);
        assertThat(buy.order().getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(buy.order().getFilledQuantity()).isEqualTo(100);
        assertThat(escrow(bob)).isEqualTo(50 * 450);
        assertThat(cash(bob)).isEqualTo(bobCash - 150 * 450);
        assertThat(registry.book(marketId).bestBid()).hasValue(450);

        orderService.cancel(bob, buy.order().getId());
        assertThat(escrow(bob)).isZero();
        assertThat(cash(bob)).isEqualTo(bobCash - 100 * 450);
        assertThat(registry.book(marketId).bestBid()).isEmpty();
        assertThat(orderRepository.findById(buy.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void marketBuySweepsBookAndCancelsUnfilledRemainder() {
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 50);
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 460L, 50);
        long carolCash = cash(carol);

        PlacedOrder buy = orderService.place(carol, marketId, Side.BUY, OrderType.MARKET, null, 150);
        assertThat(buy.trades()).extracting(TradeEntity::getPriceCents).containsExactly(450L, 460L);
        assertThat(buy.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(buy.order().getFilledQuantity()).isEqualTo(100);
        assertThat(cash(carol)).isEqualTo(carolCash - (50 * 450 + 50 * 460));
        assertThat(escrow(carol)).isZero();
        assertThat(shares(carol, marketId)).isEqualTo(100);
    }

    @Test
    void marketBuyAgainstEmptyBookIsCancelledWithoutTouchingCash() {
        long carolCash = cash(carol);
        PlacedOrder buy = orderService.place(carol, marketId, Side.BUY, OrderType.MARKET, null, 10);
        assertThat(buy.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(buy.trades()).isEmpty();
        assertThat(cash(carol)).isEqualTo(carolCash);
        assertThat(escrow(carol)).isZero();
    }

    @Test
    void sellerCannotSellMoreThanAvailableAndBuyerNeedsFunds() {
        assertThatThrownBy(() -> orderService.place(bob, marketId, Side.SELL, OrderType.LIMIT, 450L, 401))
                .isInstanceOf(InsufficientSharesException.class);
        assertThatThrownBy(() -> orderService.place(carol, marketId, Side.SELL, OrderType.LIMIT, 450L, 1))
                .isInstanceOf(InsufficientSharesException.class);
        assertThatThrownBy(() -> orderService.place(carol, marketId, Side.BUY, OrderType.LIMIT, 5_000L, 1000))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(escrow(carol)).isZero();
        assertThat(registry.book(marketId).snapshot().bids()).isEmpty();
        assertThat(registry.book(marketId).snapshot().asks()).isEmpty();
    }

    @Test
    void haltedMarketAndNonOpenSlateRejectOrders() {
        lifecycle.haltMarket(marketId, "injury");
        assertThatThrownBy(() -> orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 1))
                .isInstanceOf(TradingRejectedException.class)
                .hasMessageContaining("halted");
        lifecycle.resumeMarket(marketId, "repriced");

        MarketFixture scheduled = scheduledMarket(LOW_PROJECTION);
        assertThatThrownBy(() -> orderService.place(carol, scheduled.market().getId(), Side.BUY, OrderType.LIMIT, 450L, 1))
                .isInstanceOf(TradingRejectedException.class)
                .hasMessageContaining("SCHEDULED");
    }

    @Test
    void closingTheSlateCancelsRestingOrdersAndReleasesEscrow() {
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 100);
        PlacedOrder bid = orderService.place(bob, marketId, Side.BUY, OrderType.LIMIT, 300L, 50);
        assertThat(escrow(bob)).isEqualTo(15_000);

        lifecycle.recordGameStatus(f.game().getId(), GameStatus.FINAL, "test");

        assertThat(lifecycle.transition(f.slate().getId(), SlateStatus.CLOSED, "noop")).isFalse();
        assertThat(escrow(bob)).isZero();
        assertThat(position(alice, marketId).getLockedQuantity()).isZero();
        assertThat(orderRepository.findById(bid.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(registry.book(marketId).snapshot().bids()).isEmpty();
        assertThat(registry.book(marketId).snapshot().asks()).isEmpty();
    }

    @Test
    void bookRebuildsFromDatabaseWithIdenticalDepth() {
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 460L, 40);
        orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 450L, 30);
        orderService.place(bob, marketId, Side.BUY, OrderType.LIMIT, 420L, 20);
        orderService.place(carol, marketId, Side.BUY, OrderType.LIMIT, 420L, 10);
        BookSnapshot before = registry.book(marketId).snapshot();

        registry.withMarketLock(marketId, () -> {
            registry.rebuildMarket(marketId);
            return null;
        });

        assertThat(registry.book(marketId).snapshot()).isEqualTo(before);
        // Time priority survives the rebuild: bob (earlier) fills before carol at 420.
        PlacedOrder sell = orderService.place(alice, marketId, Side.SELL, OrderType.LIMIT, 420L, 20);
        assertThat(sell.trades()).singleElement().satisfies(t -> assertThat(t.getBuyerUserId()).isEqualTo(bob));
    }

    @Test
    void randomTradingKeepsCashAndSharesConservedAndReconciled() {
        List<UUID> traders = List.of(alice, bob, carol);
        Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            UUID trader = traders.get(random.nextInt(3));
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int quantity = 1 + random.nextInt(60);
            try {
                if (random.nextInt(10) < 2) {
                    orderService.place(trader, marketId, side, OrderType.MARKET, null, quantity);
                } else {
                    orderService.place(trader, marketId, side, OrderType.LIMIT, 380L + random.nextInt(80), quantity);
                }
            } catch (InsufficientSharesException | InsufficientFundsException expected) {
                // rejected orders must leave no trace; checked by the invariants below
            }
            if (random.nextInt(10) < 2) {
                List<OrderEntity> resting = orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(
                        marketId, List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));
                if (!resting.isEmpty()) {
                    OrderEntity victim = resting.get(random.nextInt(resting.size()));
                    orderService.cancel(victim.getUserId(), victim.getId());
                }
            }
        }
        assertInvariants(traders);
    }

    @Test
    void concurrentOrdersOnOneMarketStayConsistent() throws Exception {
        List<UUID> traders = List.of(alice, bob, carol);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int seed = t;
            pool.submit(() -> {
                Random random = new Random(seed);
                start.await();
                for (int i = 0; i < 20; i++) {
                    UUID trader = traders.get(random.nextInt(3));
                    Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                    try {
                        orderService.place(trader, marketId, side, OrderType.LIMIT,
                                390L + random.nextInt(40), 1 + random.nextInt(30));
                    } catch (InsufficientSharesException | InsufficientFundsException ignored) {
                        // expected under contention
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        assertInvariants(traders);
    }

    private void assertInvariants(List<UUID> traders) {
        // Shares: exactly the IPO supply exists, split across traders and the house.
        assertThat(positionRepository.totalSharesInMarket(marketId)).isEqualTo(1000);
        // Cash: the ledger is zero-sum overall.
        long allBalances = accountRepository.findAll().stream().mapToLong(AccountEntity::getBalanceCents).sum();
        assertThat(allBalances).isZero();
        for (UUID trader : traders) {
            // Escrow balance equals the escrow still attached to this trader's resting buy orders.
            long restingEscrow = orderRepository.findByUserIdOrderByCreatedAtDesc(trader).stream()
                    .filter(o -> o.getStatus().isResting())
                    .mapToLong(OrderEntity::getEscrowCents).sum();
            assertThat(escrow(trader)).as("escrow reconciles for %s", trader).isEqualTo(restingEscrow);
            // Locked shares equal the shares attached to this trader's resting sell orders.
            int restingLocked = orderRepository.findByUserIdOrderByCreatedAtDesc(trader).stream()
                    .filter(o -> o.getStatus().isResting())
                    .mapToInt(OrderEntity::getLockedShares).sum();
            int locked = positionRepository.findByUserIdAndMarketId(trader, marketId)
                    .map(PositionEntity::getLockedQuantity).orElse(0);
            assertThat(locked).as("locked shares reconcile for %s", trader).isEqualTo(restingLocked);
            assertThat(cash(trader)).isGreaterThanOrEqualTo(0);
        }
        // The in-memory book matches the database's view of resting orders.
        BookSnapshot live = registry.book(marketId).snapshot();
        registry.withMarketLock(marketId, () -> {
            registry.rebuildMarket(marketId);
            return null;
        });
        assertThat(registry.book(marketId).snapshot()).isEqualTo(live);
    }
}
