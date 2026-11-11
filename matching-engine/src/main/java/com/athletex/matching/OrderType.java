package com.athletex.matching;

public enum OrderType {
    /** Fills at the limit price or better; any remainder rests on the book. */
    LIMIT,
    /** Fills against the book at any price; any remainder is cancelled, never rests. */
    MARKET
}
