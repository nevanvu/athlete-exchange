package com.athletex.matching;

import java.util.List;

/**
 * Outcome of submitting one order. Quantities always account for the whole order:
 * filled + restingQuantity + cancelledQuantity == the order's original quantity.
 */
public record MatchResult(String orderId, List<Fill> fills, int restingQuantity, int cancelledQuantity) {

    public int filledQuantity() {
        return fills.stream().mapToInt(Fill::quantity).sum();
    }

    public boolean fullyFilled() {
        return restingQuantity == 0 && cancelledQuantity == 0;
    }
}
