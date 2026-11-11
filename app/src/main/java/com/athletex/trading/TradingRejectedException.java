package com.athletex.trading;

/** The order is well-formed but the market cannot accept it right now (slate phase, halt). */
public class TradingRejectedException extends RuntimeException {

    public TradingRejectedException(String message) {
        super(message);
    }
}
