package com.athletex.marketmaker;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.halts.HaltResumer;
import com.athletex.market.MarketStatus;
import com.athletex.matching.BookSnapshot;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.settlement.AbstractLiveSlateTest;
import com.athletex.trading.MatchingEngineRegistry;
import com.athletex.trading.OrderEntity;
import com.athletex.trading.OrderRepository;
import com.athletex.trading.OrderStatus;
import com.athletex.trading.PlacedOrder;
import com.athletex.trading.PositionEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MarketMakerTest extends AbstractLiveSlateTest {

    @Autowired private MarketMakerService marketMaker;
    @Autowired private HaltResumer resumer;
    @Autowired private MatchingEngineRegistry registry;
    @Autowired private OrderRepository orderRepository;

    private BookSnapshot book(UUID market) {
        return registry.withMarketLock(market, () -> registry.book(market).snapshot());
    }

    private List<UUID> houseOrderIds(UUID market) {
        return orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(market,
                        List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED)).stream()
                .filter(o -> o.getUserId().equals(PositionEntity.HOUSE_USER_ID))
                .map(OrderEntity::getId)
                .toList();
    }

    private int houseShares(UUID market) {
        return positionRepository.findByUserIdAndMarketId(PositionEntity.HOUSE_USER_ID, market)
                .map(PositionEntity::getQuantity).orElse(0);
    }

    @Test
    void quotesBothSidesAroundFairValueFromHouseInventory() {
        Slate s = newSlate(40);
        s.openMarket(); // no IPO bids: the house keeps all 1000 shares
        UUID market = s.markets.get(0);
        assertThat(houseShares(market)).isEqualTo(1000);

        MarketMakerService.Quote quote = marketMaker.quoteMarket(market);

        assertThat(quote.spread()).isEqualTo(0.02);
        assertThat(quote.bidCents()).isEqualTo(980);
        assertThat(quote.askCents()).isEqualTo(1020);
        assertThat(book(market).bids()).containsExactly(new BookSnapshot.Level(980, 50));
        assertThat(book(market).asks()).containsExactly(new BookSnapshot.Level(1020, 50));
    }

    @Test
    void requotesOnlyWhenTheQuoteChangesAndWidensWithVolatility() {
        Slate s = newSlate(40);
        s.openMarket();
        UUID market = s.markets.get(0);
        marketMaker.quoteMarket(market);
        List<UUID> first = houseOrderIds(market);

        marketMaker.quoteMarket(market);
        assertThat(houseOrderIds(market)).as("unchanged quote is left alone").isEqualTo(first);

        s.apply(s.line(0, 4, 0, 0, false)); // fair value $10.00 -> $11.00 (10%: below the halt threshold)
        MarketMakerService.Quote quote = marketMaker.quoteMarket(market);

        // One 10% move -> volatility 0.10 -> spread min(5%, 2% + 2 x 10%) = 5%.
        assertThat(quote.spread()).isEqualTo(0.05);
        assertThat(quote.bidCents()).isEqualTo(1045);
        assertThat(quote.askCents()).isEqualTo(1155);
        assertThat(houseOrderIds(market)).hasSize(2).doesNotContainAnyElementsOf(first);
        assertThat(orderRepository.findAllById(first)).allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    void usersTradeAgainstTheHouseAndItReplenishes() {
        Slate s = newSlate(40);
        s.openMarket();
        UUID market = s.markets.get(0);
        UUID bob = newTrader();
        marketMaker.quoteMarket(market);

        PlacedOrder buy = orderService.place(bob, market, Side.BUY, OrderType.LIMIT, 1030L, 50);
        assertThat(buy.trades()).singleElement().satisfies(t -> {
            assertThat(t.getPriceCents()).isEqualTo(1020);
            assertThat(t.getSellerUserId()).isEqualTo(PositionEntity.HOUSE_USER_ID);
        });
        assertThat(houseShares(market)).isEqualTo(950);
        assertThat(book(market).asks()).isEmpty();

        marketMaker.quoteMarket(market);
        assertThat(book(market).asks()).containsExactly(new BookSnapshot.Level(1020, 50));
    }

    @Test
    void noInventoryMeansNoAskAndTheInventoryCapStopsBids() {
        Slate soldOut = newSlate(40);
        UUID alice = newTrader();
        soldOut.toIpo();
        ipoService.bid(alice, soldOut.markets.get(0), 1000, 1000);
        soldOut.openMarket();
        MarketMakerService.Quote bidOnly = marketMaker.quoteMarket(soldOut.markets.get(0));
        assertThat(bidOnly.askCents()).isNull();
        assertThat(bidOnly.bidCents()).isEqualTo(980);

        Slate stocked = newSlate(40);
        stocked.openMarket();
        MarketMakerService.Settings tightCap = new MarketMakerService.Settings(50, 1020, 0.02, 0.05, 10);
        MarketMakerService.Quote askOnly = marketMaker.quoteMarket(stocked.markets.get(0), tightCap);
        assertThat(askOnly.bidCents()).as("1000 held + 50 would exceed the 1020 cap").isNull();
        assertThat(askOnly.askCents()).isEqualTo(1020);
        assertThat(book(stocked.markets.get(0)).bids()).isEmpty();
    }

    @Test
    void staleHouseQuotesArePulledBeforeAHaltedMarketReopens() {
        Slate s = newSlate(40);
        s.toIpo();
        s.apply(s.line(0, 12, 0, 0, false));
        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        s.openMarket();
        UUID market = s.markets.get(0);
        // Fair value went $10.00 -> $13.00 -> $8.00 in the first half: volatile, so the spread is at its cap.
        MarketMakerService.Quote beforeHalt = marketMaker.quoteMarket(market);
        assertThat(beforeHalt.spread()).isEqualTo(0.05);
        assertThat(beforeHalt.askCents()).isEqualTo(840);
        assertThat(book(market).asks()).containsExactly(new BookSnapshot.Level(840, 50));

        s.apply(s.injury(0, InjuryStatus.OUT));                // halted, fair value $3.00
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.HALTED);
        assertThat(marketMaker.quoteMarket(market)).as("silent while halted").isNull();
        assertThat(houseOrderIds(market)).isEmpty();

        // Even if quotes were somehow left on the book, the resumer clears them before reopening.
        resumer.resumeDue(s.market(0).getResumeAfter());
        assertThat(s.market(0).getStatus()).isEqualTo(MarketStatus.ACTIVE);
        assertThat(book(market).asks()).isEmpty();

        MarketMakerService.Quote fresh = marketMaker.quoteMarket(market);
        assertThat(fresh.askCents()).isLessThan(400);
        assertThat(book(market).asks()).containsExactly(new BookSnapshot.Level(fresh.askCents(), 50));
    }
}
