package com.athletex.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IpoAuctionTest extends AbstractTradingTest {

    @Test
    void oversubscribedAuctionClearsAtMarginalBidAndRefundsTheDifference() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION); // reserve 200c, supply 1000
        toIpo(f);
        UUID a = newTrader();
        UUID b = newTrader();
        UUID c = newTrader();
        UUID marketId = f.market().getId();
        long houseBefore = houseCash();

        ipoService.bid(a, marketId, 500, 600);
        ipoService.bid(b, marketId, 400, 600);
        ipoService.bid(c, marketId, 300, 300);
        assertThat(escrow(a)).isEqualTo(300_000);
        assertThat(cash(a)).isEqualTo(SEED_CENTS - 300_000);

        openMarket(f);

        // Cumulative demand covers 1000 shares at B's price, so everyone pays 400.
        assertThat(reload(f.market()).getIpoClearingPriceCents()).isEqualTo(400);
        assertThat(shares(a, marketId)).isEqualTo(600);
        assertThat(shares(b, marketId)).isEqualTo(400);
        assertThat(shares(c, marketId)).isZero();
        assertThat(position(a, marketId).averageCostCents()).isEqualTo(400);

        assertThat(cash(a)).isEqualTo(SEED_CENTS - 600 * 400);
        assertThat(cash(b)).isEqualTo(SEED_CENTS - 400 * 400);
        assertThat(cash(c)).isEqualTo(SEED_CENTS);
        assertThat(escrow(a)).isZero();
        assertThat(escrow(b)).isZero();
        assertThat(escrow(c)).isZero();
        assertThat(houseCash() - houseBefore).isEqualTo(600 * 400 + 400 * 400);
        assertThat(shares(PositionEntity.HOUSE_USER_ID, marketId)).isZero();

        List<IpoBidEntity> bids = bidRepository.findByUserIdOrderByCreatedAtDesc(c);
        assertThat(bids).singleElement().satisfies(bid -> {
            assertThat(bid.getStatus()).isEqualTo(IpoBidEntity.Status.REFUNDED);
            assertThat(bid.getAllocatedQuantity()).isZero();
        });
    }

    @Test
    void undersubscribedAuctionClearsAtReserveAndHouseKeepsUnsold() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        toIpo(f);
        UUID a = newTrader();
        UUID marketId = f.market().getId();
        long houseBefore = houseCash();

        ipoService.bid(a, marketId, 500, 300);
        openMarket(f);

        assertThat(reload(f.market()).getIpoClearingPriceCents()).isEqualTo(200);
        assertThat(shares(a, marketId)).isEqualTo(300);
        assertThat(cash(a)).isEqualTo(SEED_CENTS - 300 * 200);
        assertThat(escrow(a)).isZero();
        assertThat(shares(PositionEntity.HOUSE_USER_ID, marketId)).isEqualTo(700);
        assertThat(houseCash() - houseBefore).isEqualTo(300 * 200);
        assertThat(positionRepository.totalSharesInMarket(marketId)).isEqualTo(1000);
    }

    @Test
    void timePriorityBreaksTiesAtTheClearingPrice() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        toIpo(f);
        UUID x = newTrader();
        UUID y = newTrader();
        UUID z = newTrader();
        UUID marketId = f.market().getId();

        ipoService.bid(x, marketId, 400, 500);
        ipoService.bid(y, marketId, 400, 500);
        ipoService.bid(z, marketId, 400, 200);
        openMarket(f);

        assertThat(reload(f.market()).getIpoClearingPriceCents()).isEqualTo(400);
        assertThat(shares(x, marketId)).isEqualTo(500);
        assertThat(shares(y, marketId)).isEqualTo(500);
        assertThat(shares(z, marketId)).isZero();
        assertThat(cash(z)).isEqualTo(SEED_CENTS);
    }

    @Test
    void bidsBelowReserveAreRefunded() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        toIpo(f);
        UUID a = newTrader();
        ipoService.bid(a, f.market().getId(), 150, 100);
        openMarket(f);
        assertThat(shares(a, f.market().getId())).isZero();
        assertThat(cash(a)).isEqualTo(SEED_CENTS);
        assertThat(escrow(a)).isZero();
    }

    @Test
    void biddingIsOnlyAllowedDuringIpo() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        UUID a = newTrader();
        assertThatThrownBy(() -> ipoService.bid(a, f.market().getId(), 300, 10))
                .isInstanceOf(TradingRejectedException.class);
        toIpo(f);
        openMarket(f);
        assertThatThrownBy(() -> ipoService.bid(a, f.market().getId(), 300, 10))
                .isInstanceOf(TradingRejectedException.class);
        assertThat(cash(a)).isEqualTo(SEED_CENTS);
    }

    @Test
    void cancellingABidReleasesEscrow() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        toIpo(f);
        UUID a = newTrader();
        IpoBidEntity bid = ipoService.bid(a, f.market().getId(), 300, 100);
        assertThat(escrow(a)).isEqualTo(30_000);

        ipoService.cancelBid(a, bid.getId());
        assertThat(escrow(a)).isZero();
        assertThat(cash(a)).isEqualTo(SEED_CENTS);
        assertThatThrownBy(() -> ipoService.cancelBid(a, bid.getId())).isInstanceOf(TradingRejectedException.class);
    }

    @Test
    void bidderCannotEscrowMoreThanTheyHave() {
        MarketFixture f = scheduledMarket(LOW_PROJECTION);
        toIpo(f);
        UUID a = newTrader();
        assertThatThrownBy(() -> ipoService.bid(a, f.market().getId(), 10_000, 1000))
                .isInstanceOf(com.athletex.ledger.InsufficientFundsException.class);
        assertThat(bidRepository.findByUserIdOrderByCreatedAtDesc(a)).isEmpty();
    }
}
