package com.athletex.ledger;

import java.util.UUID;

/** One leg of a ledger transaction: positive credits the account, negative debits it. */
public record Posting(UUID accountId, long amountCents) {
}
