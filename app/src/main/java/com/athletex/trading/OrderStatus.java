package com.athletex.trading;

public enum OrderStatus {
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED;

    public boolean isResting() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
