package com.athletex.ledger;

public enum AccountType {
    USER_CASH(true),
    USER_ESCROW(true),
    HOUSE(false),
    SETTLEMENT_POOL(false);

    private final boolean mustStayNonNegative;

    AccountType(boolean mustStayNonNegative) {
        this.mustStayNonNegative = mustStayNonNegative;
    }

    public boolean mustStayNonNegative() {
        return mustStayNonNegative;
    }
}
