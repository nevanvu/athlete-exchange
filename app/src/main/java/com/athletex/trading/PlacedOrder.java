package com.athletex.trading;

import java.util.List;

public record PlacedOrder(OrderEntity order, List<TradeEntity> trades) {
}
