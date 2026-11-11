package com.athletex.ledger;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, long requestedCents, long balanceCents) {
        super("account %s cannot absorb %d cents (balance %d)".formatted(accountId, requestedCents, balanceCents));
    }
}
