package com.athletex.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deliberately naive O(n) matcher with the same semantics as OrderBook, used as the
 * oracle in property tests. Scans the whole resting list for the best opposite order
 * (price first, then arrival order) on every fill.
 */
class ReferenceBook {

    private static final class Rest {
        final String id;
        final Side side;
        final long price;
        final long arrival;
        final String owner;
        int remaining;

        Rest(String id, Side side, long price, long arrival, String owner, int remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.owner = owner;
            this.remaining = remaining;
        }
    }

    private final List<Rest> resting = new ArrayList<>();
    private long arrivals = 0;

    MatchResult submit(NewOrder order) {
        List<Fill> fills = new ArrayList<>();
        int remaining = order.quantity();
        boolean selfTrade = false;

        while (remaining > 0) {
            Rest best = resting.stream()
                    .filter(r -> r.side == order.side().opposite())
                    .min(Comparator
                            .comparingLong((Rest r) -> order.side() == Side.BUY ? r.price : -r.price)
                            .thenComparingLong(r -> r.arrival))
                    .orElse(null);
            if (best == null) {
                break;
            }
            boolean crosses = order.type() == OrderType.MARKET
                    || (order.side() == Side.BUY ? order.priceCents() >= best.price : order.priceCents() <= best.price);
            if (!crosses) {
                break;
            }
            if (order.owner() != null && order.owner().equals(best.owner)) {
                selfTrade = true;
                break;
            }
            int quantity = Math.min(remaining, best.remaining);
            best.remaining -= quantity;
            remaining -= quantity;
            fills.add(new Fill(order.orderId(), best.id, best.price, quantity));
            if (best.remaining == 0) {
                resting.remove(best);
            }
        }

        int restingQty = 0;
        int cancelled = 0;
        if (remaining > 0) {
            if (order.type() == OrderType.LIMIT && !selfTrade) {
                resting.add(new Rest(order.orderId(), order.side(), order.priceCents(), arrivals++, order.owner(), remaining));
                restingQty = remaining;
            } else {
                cancelled = remaining;
            }
        }
        return new MatchResult(order.orderId(), List.copyOf(fills), restingQty, cancelled, selfTrade);
    }

    int cancel(String orderId) {
        for (Rest rest : resting) {
            if (rest.id.equals(orderId)) {
                resting.remove(rest);
                return rest.remaining;
            }
        }
        return 0;
    }

    BookSnapshot snapshot() {
        return new BookSnapshot(levels(Side.BUY), levels(Side.SELL));
    }

    private List<BookSnapshot.Level> levels(Side side) {
        return resting.stream()
                .filter(r -> r.side == side)
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.price,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.summingInt(r -> r.remaining)))
                .entrySet().stream()
                .sorted(side == Side.BUY
                        ? java.util.Map.Entry.<Long, Integer>comparingByKey().reversed()
                        : java.util.Map.Entry.comparingByKey())
                .map(e -> new BookSnapshot.Level(e.getKey(), e.getValue()))
                .toList();
    }
}
