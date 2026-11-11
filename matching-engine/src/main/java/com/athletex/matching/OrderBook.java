package com.athletex.matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A single market's limit order book with price-time priority.
 *
 * <p>Not thread-safe by design: callers serialize access per market (one matcher per
 * athlete market). Matching is deterministic — the same order sequence always produces
 * the same fills — which is what makes replay-based testing possible.
 */
public final class OrderBook {

    private static final class RestingOrder {
        final String orderId;
        final Side side;
        final long priceCents;
        int remaining;

        RestingOrder(String orderId, Side side, long priceCents, int remaining) {
            this.orderId = orderId;
            this.side = side;
            this.priceCents = priceCents;
            this.remaining = remaining;
        }
    }

    /** Bids sorted best-first (highest price), asks best-first (lowest price); FIFO within a level. */
    private final NavigableMap<Long, Deque<RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Deque<RestingOrder>> asks = new TreeMap<>();
    private final Map<String, RestingOrder> restingById = new HashMap<>();

    public MatchResult submit(NewOrder order) {
        validate(order);
        if (restingById.containsKey(order.orderId())) {
            throw new IllegalArgumentException("duplicate order id " + order.orderId());
        }

        List<Fill> fills = new ArrayList<>();
        int remaining = order.quantity();
        NavigableMap<Long, Deque<RestingOrder>> opposite = order.side() == Side.BUY ? asks : bids;

        while (remaining > 0 && !opposite.isEmpty()) {
            long bestPrice = opposite.firstKey();
            if (order.type() == OrderType.LIMIT && !crosses(order.side(), order.priceCents(), bestPrice)) {
                break;
            }
            Deque<RestingOrder> level = opposite.firstEntry().getValue();
            RestingOrder maker = level.peekFirst();
            int quantity = Math.min(remaining, maker.remaining);
            maker.remaining -= quantity;
            remaining -= quantity;
            fills.add(new Fill(order.orderId(), maker.orderId, bestPrice, quantity));
            if (maker.remaining == 0) {
                level.pollFirst();
                restingById.remove(maker.orderId);
            }
            if (level.isEmpty()) {
                opposite.remove(bestPrice);
            }
        }

        int resting = 0;
        int cancelled = 0;
        if (remaining > 0) {
            if (order.type() == OrderType.LIMIT) {
                RestingOrder rest = new RestingOrder(order.orderId(), order.side(), order.priceCents(), remaining);
                bookFor(order.side()).computeIfAbsent(order.priceCents(), p -> new ArrayDeque<>()).addLast(rest);
                restingById.put(order.orderId(), rest);
                resting = remaining;
            } else {
                cancelled = remaining; // market remainder never rests
            }
        }
        return new MatchResult(order.orderId(), List.copyOf(fills), resting, cancelled);
    }

    /** Cancels a resting order. Returns the cancelled remaining quantity, or 0 if unknown/already gone. */
    public int cancel(String orderId) {
        RestingOrder resting = restingById.remove(orderId);
        if (resting == null) {
            return 0;
        }
        NavigableMap<Long, Deque<RestingOrder>> book = bookFor(resting.side);
        Deque<RestingOrder> level = book.get(resting.priceCents);
        level.remove(resting);
        if (level.isEmpty()) {
            book.remove(resting.priceCents);
        }
        return resting.remaining;
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    public BookSnapshot snapshot() {
        return new BookSnapshot(levels(bids), levels(asks));
    }

    private static List<BookSnapshot.Level> levels(NavigableMap<Long, Deque<RestingOrder>> book) {
        List<BookSnapshot.Level> levels = new ArrayList<>(book.size());
        for (Map.Entry<Long, Deque<RestingOrder>> entry : book.entrySet()) {
            int quantity = entry.getValue().stream().mapToInt(o -> o.remaining).sum();
            levels.add(new BookSnapshot.Level(entry.getKey(), quantity));
        }
        return levels;
    }

    private NavigableMap<Long, Deque<RestingOrder>> bookFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static boolean crosses(Side takerSide, long limitPrice, long bestOppositePrice) {
        return takerSide == Side.BUY ? limitPrice >= bestOppositePrice : limitPrice <= bestOppositePrice;
    }

    private static void validate(NewOrder order) {
        if (order.orderId() == null || order.orderId().isBlank()) {
            throw new IllegalArgumentException("order id is required");
        }
        if (order.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (order.type() == OrderType.LIMIT && order.priceCents() <= 0) {
            throw new IllegalArgumentException("limit price must be positive");
        }
    }
}
