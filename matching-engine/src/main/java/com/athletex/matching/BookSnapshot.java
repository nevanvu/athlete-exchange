package com.athletex.matching;

import java.util.List;

/** Aggregated depth view: bids best-first (descending price), asks best-first (ascending price). */
public record BookSnapshot(List<Level> bids, List<Level> asks) {

    public record Level(long priceCents, int quantity) {
    }
}
