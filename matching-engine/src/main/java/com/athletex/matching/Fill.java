package com.athletex.matching;

/** One trade: the incoming (taker) order matched a resting (maker) order at the maker's price. */
public record Fill(String takerOrderId, String makerOrderId, long priceCents, int quantity) {
}
