package com.athletex.trading;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * No single trader may hold more than a set number of shares of one athlete, counting shares held,
 * shares on open buy orders and open IPO bids, so a market cannot be cornered. The house (market
 * maker) is exempt. 0 disables the limit.
 */
@Component
public class PositionLimits {

    private static final List<OrderStatus> RESTING = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    private final PositionRepository positions;
    private final OrderRepository orders;
    private final IpoBidRepository bids;
    private final int maxShares;

    public PositionLimits(PositionRepository positions, OrderRepository orders, IpoBidRepository bids,
                          @Value("${athletex.limits.max-position-shares:250}") int maxShares) {
        this.positions = positions;
        this.orders = orders;
        this.bids = bids;
        this.maxShares = maxShares;
    }

    public int maxShares() {
        return maxShares;
    }

    /** Throws if buying {@code quantity} more could take the user over the limit. */
    public void checkBuy(UUID userId, UUID marketId, int quantity) {
        if (maxShares <= 0 || PositionEntity.HOUSE_USER_ID.equals(userId)) {
            return;
        }
        int held = positions.findByUserIdAndMarketId(userId, marketId).map(PositionEntity::getQuantity).orElse(0);
        int openBuys = orders.findByMarketIdAndStatusInOrderByCreatedAtAsc(marketId, RESTING).stream()
                .filter(o -> o.getUserId().equals(userId) && o.getSide() == com.athletex.matching.Side.BUY)
                .mapToInt(OrderEntity::getRemainingQuantity).sum();
        int openBids = bids.findByMarketIdAndStatusOrderByMaxPriceCentsDescCreatedAtAsc(marketId, IpoBidEntity.Status.OPEN)
                .stream().filter(b -> b.getUserId().equals(userId)).mapToInt(IpoBidEntity::getQuantity).sum();
        int committed = held + openBuys + openBids;
        if (committed + quantity > maxShares) {
            int room = Math.max(0, maxShares - committed);
            throw new TradingRejectedException("You can hold at most " + maxShares + " shares of one athlete. You have "
                    + committed + " counting open orders, so you can buy " + room + " more.");
        }
    }
}
