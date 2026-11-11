package com.athletex.trading;

import java.util.UUID;

public class InsufficientSharesException extends RuntimeException {

    public InsufficientSharesException(UUID marketId, int requested, int available) {
        super("market %s: cannot lock %d shares, only %d available".formatted(marketId, requested, available));
    }
}
