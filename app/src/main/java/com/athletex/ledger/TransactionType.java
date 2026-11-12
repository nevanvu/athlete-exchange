package com.athletex.ledger;

public enum TransactionType {
    SEED_GRANT,
    TRANSFER,
    ESCROW_LOCK,
    ESCROW_RELEASE,
    TRADE_SETTLEMENT,
    IPO_ALLOCATION,
    SLATE_SETTLEMENT,
    /**
     * A stat revision after settlement. It may take a user's cash below zero: refusing a
     * downward correction would leave the ledger disagreeing with the official result.
     */
    SETTLEMENT_CORRECTION(true);

    private final boolean permitsOverdraft;

    TransactionType() {
        this(false);
    }

    TransactionType(boolean permitsOverdraft) {
        this.permitsOverdraft = permitsOverdraft;
    }

    public boolean permitsOverdraft() {
        return permitsOverdraft;
    }
}
