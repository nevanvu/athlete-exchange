package com.athletex.trading;

import java.util.List;

/** {@code selfTradePrevented}: the order stopped at the user's own resting order; the rest was cancelled. */
public record PlacedOrder(OrderEntity order, List<TradeEntity> trades, boolean selfTradePrevented) {
}
