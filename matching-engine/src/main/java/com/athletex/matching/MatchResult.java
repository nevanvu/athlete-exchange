package com.athletex.matching;

import java.util.List;

/**
 * Outcome of submitting one order. Quantities always account for the whole order:
 * filled + restingQuantity + cancelledQuantity == the order's original quantity.
 * {@code selfTradePrevented} means matching stopped at the owner's own resting order and the
 * remainder was cancelled rather than traded against itself (or rested across it).
 */
public record MatchResult(String orderId, List<Fill> fills, int restingQuantity, int cancelledQuantity,
                          boolean selfTradePrevented) {

    public MatchResult(String orderId, List<Fill> fills, int restingQuantity, int cancelledQuantity) {
        this(orderId, fills, restingQuantity, cancelledQuantity, false);
    }

    public int filledQuantity() {
        return fills.stream().mapToInt(Fill::quantity).sum();
    }

    public boolean fullyFilled() {
        return restingQuantity == 0 && cancelledQuantity == 0;
    }
}
