package com.athletex.matching;

/**
 * An incoming order. {@code priceCents} is the limit price; it is ignored for MARKET orders.
 * {@code owner} enables self-trade prevention: an order never matches a resting order with the
 * same owner. Null means anonymous (no prevention).
 */
public record NewOrder(String orderId, Side side, OrderType type, long priceCents, int quantity, String owner) {

    public NewOrder(String orderId, Side side, OrderType type, long priceCents, int quantity) {
        this(orderId, side, type, priceCents, quantity, null);
    }

    public static NewOrder limit(String orderId, Side side, long priceCents, int quantity) {
        return new NewOrder(orderId, side, OrderType.LIMIT, priceCents, quantity);
    }

    public static NewOrder market(String orderId, Side side, int quantity) {
        return new NewOrder(orderId, side, OrderType.MARKET, 0L, quantity);
    }

    public NewOrder ownedBy(String owner) {
        return new NewOrder(orderId, side, type, priceCents, quantity, owner);
    }
}
