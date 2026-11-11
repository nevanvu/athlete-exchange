package com.athletex.matching;

/**
 * An incoming order. {@code priceCents} is the limit price; it is ignored for MARKET orders.
 */
public record NewOrder(String orderId, Side side, OrderType type, long priceCents, int quantity) {

    public static NewOrder limit(String orderId, Side side, long priceCents, int quantity) {
        return new NewOrder(orderId, side, OrderType.LIMIT, priceCents, quantity);
    }

    public static NewOrder market(String orderId, Side side, int quantity) {
        return new NewOrder(orderId, side, OrderType.MARKET, 0L, quantity);
    }
}
