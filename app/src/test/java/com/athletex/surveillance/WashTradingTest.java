package com.athletex.surveillance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.settlement.AbstractLiveSlateTest;
import com.athletex.trading.IpoBidRepository;
import com.athletex.trading.OrderRepository;
import com.athletex.trading.OrderStatus;
import com.athletex.trading.PlacedOrder;
import com.athletex.trading.PositionLimits;
import com.athletex.trading.TradeRepository;
import com.athletex.trading.TradingRejectedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WashTradingTest extends AbstractLiveSlateTest {

    @Autowired private SurveillanceService surveillance;
    @Autowired private SurveillanceAlertRepository alerts;
    @Autowired private TradeRepository trades;
    @Autowired private OrderRepository orders;
    @Autowired private IpoBidRepository bids;

    /** Alice and Bob each own 500 shares bought at the $10.00 IPO. */
    private record Market(Slate slate, UUID id, UUID alice, UUID bob) {
    }

    private Market openWithHolders() {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        UUID bob = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 500);
        ipoService.bid(bob, s.markets.get(0), 1000, 500);
        s.openMarket();
        return new Market(s, s.markets.get(0), alice, bob);
    }

    @Test
    void anOrderNeverTradesWithTheSameAccountsRestingOrder() {
        Market m = openWithHolders();
        orderService.place(m.bob(), m.id(), Side.SELL, OrderType.LIMIT, 1000L, 20);   // best ask: someone else
        orderService.place(m.alice(), m.id(), Side.SELL, OrderType.LIMIT, 1010L, 20); // alice's own ask

        PlacedOrder buy = orderService.place(m.alice(), m.id(), Side.BUY, OrderType.LIMIT, 1050L, 50);

        assertThat(buy.trades()).singleElement().satisfies(t -> assertThat(t.getSellerUserId()).isEqualTo(m.bob()));
        assertThat(buy.selfTradePrevented()).isTrue();
        assertThat(buy.order().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(buy.order().getEscrowCents()).isZero();
        assertThat(trades.findTop50ByMarketIdOrderByExecutedAtDesc(m.id()))
                .noneSatisfy(t -> assertThat(t.getBuyerUserId()).isEqualTo(t.getSellerUserId()));

        SurveillanceAlertEntity alert = alerts.findByKindAndMarketIdAndUserAAndUserB(
                SurveillanceAlertEntity.Kind.SELF_TRADE_BLOCKED, m.id(), m.alice(), m.alice()).orElseThrow();
        assertThat(alert.getOccurrences()).isEqualTo(1);
    }

    @Test
    void accountsTradingBackAndForthAreFlaggedAndCanBeReviewed() {
        Market m = openWithHolders();
        for (int i = 0; i < 3; i++) {  // the same 30 shares go round in a circle
            orderService.place(m.alice(), m.id(), Side.SELL, OrderType.LIMIT, 1000L, 30);
            orderService.place(m.bob(), m.id(), Side.BUY, OrderType.LIMIT, 1000L, 30);
            orderService.place(m.bob(), m.id(), Side.SELL, OrderType.LIMIT, 1000L, 30);
            orderService.place(m.alice(), m.id(), Side.BUY, OrderType.LIMIT, 1000L, 30);
        }

        surveillance.scanRoundTrips(Instant.now());

        UUID a = m.alice().toString().compareTo(m.bob().toString()) < 0 ? m.alice() : m.bob();
        UUID b = a.equals(m.alice()) ? m.bob() : m.alice();
        SurveillanceAlertEntity alert = alerts.findByKindAndMarketIdAndUserAAndUserB(
                SurveillanceAlertEntity.Kind.ROUND_TRIP, m.id(), a, b).orElseThrow();
        assertThat(alert.getOccurrences()).isEqualTo(6);
        assertThat(alert.getDetail()).contains("90 shares one way, 90 back").contains("Net 0 of 180");
        assertThat(alert.getStatus()).isEqualTo(SurveillanceAlertEntity.Status.OPEN);

        surveillance.dismiss(alert.getId());
        surveillance.scanRoundTrips(Instant.now());
        assertThat(alerts.findById(alert.getId()).orElseThrow().getStatus())
                .as("nothing new happened, so it stays dismissed").isEqualTo(SurveillanceAlertEntity.Status.DISMISSED);

        orderService.place(m.alice(), m.id(), Side.SELL, OrderType.LIMIT, 1000L, 30);
        orderService.place(m.bob(), m.id(), Side.BUY, OrderType.LIMIT, 1000L, 30);
        surveillance.scanRoundTrips(Instant.now());
        assertThat(alerts.findById(alert.getId()).orElseThrow().getStatus())
                .as("it continued after review, so it reopens").isEqualTo(SurveillanceAlertEntity.Status.OPEN);
    }

    @Test
    void oneWayTradingIsNotFlagged() {
        Market m = openWithHolders();
        for (int i = 0; i < 6; i++) {
            orderService.place(m.alice(), m.id(), Side.SELL, OrderType.LIMIT, 1000L + i, 10);
            orderService.place(m.bob(), m.id(), Side.BUY, OrderType.LIMIT, 1000L + i, 10);
        }
        surveillance.scanRoundTrips(Instant.now());
        assertThat(alerts.findAll()).noneSatisfy(a -> {
            assertThat(a.getMarketId()).isEqualTo(m.id());
            assertThat(a.getKind()).isEqualTo(SurveillanceAlertEntity.Kind.ROUND_TRIP);
        });
    }

    @Test
    void positionLimitsCountHoldingsOpenBuysAndIpoBids() {
        PositionLimits limits = new PositionLimits(positionRepository, orders, bids, 250);
        Slate s = newSlate(40);
        UUID carol = newTrader();
        s.toIpo();
        ipoService.bid(carol, s.markets.get(0), 1000, 200);

        assertThatThrownBy(() -> limits.checkBuy(carol, s.markets.get(0), 60))
                .isInstanceOf(TradingRejectedException.class)
                .hasMessage("You can hold at most 250 shares of one athlete. You have 200 counting open orders, so you can buy 50 more.");
        limits.checkBuy(carol, s.markets.get(0), 50);

        s.openMarket(); // carol now holds 200
        orderService.place(carol, s.markets.get(0), Side.BUY, OrderType.LIMIT, 900L, 40); // rests
        assertThatThrownBy(() -> limits.checkBuy(carol, s.markets.get(0), 11)).isInstanceOf(TradingRejectedException.class);
        limits.checkBuy(carol, s.markets.get(0), 10);
        limits.checkBuy(com.athletex.trading.PositionEntity.HOUSE_USER_ID, s.markets.get(0), 100_000); // house is exempt
    }
}
