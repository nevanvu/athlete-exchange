package com.athletex.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Drives OrderBook and the naive ReferenceBook with the same random order stream and
 * requires identical behavior, while checking structural invariants after every action.
 */
class OrderBookPropertyTest {

    @Property(tries = 300)
    void matchesReferenceAndHoldsInvariants(@ForAll @IntRange(min = 0, max = 1_000_000) int seed) {
        Random random = new Random(seed);
        OrderBook book = new OrderBook();
        ReferenceBook reference = new ReferenceBook();

        List<String> known = new ArrayList<>();
        Map<String, Integer> restingAtSubmit = new HashMap<>();
        long totalSubmitted = 0;
        long takerAccounted = 0;
        long makerFilled = 0;
        long laterCancelled = 0;

        for (int i = 0; i < 150; i++) {
            if (!known.isEmpty() && random.nextInt(100) < 15) {
                String id = known.get(random.nextInt(known.size()));
                int cancelled = book.cancel(id);
                assertThat(cancelled).as("cancel(%s) seed=%s", id, seed).isEqualTo(reference.cancel(id));
                laterCancelled += cancelled;
            } else {
                String id = "o" + i;
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                int quantity = 1 + random.nextInt(50);
                NewOrder order = random.nextInt(100) < 15
                        ? NewOrder.market(id, side, quantity)
                        : NewOrder.limit(id, side, 90 + random.nextInt(21), quantity);

                MatchResult actual = book.submit(order);
                MatchResult expected = reference.submit(order);
                assertThat(actual).as("submit(%s) seed=%s", order, seed).isEqualTo(expected);

                // Taker-side conservation: the whole order is accounted for at submit time.
                assertThat(actual.filledQuantity() + actual.restingQuantity() + actual.cancelledQuantity())
                        .as("taker conservation for %s seed=%s", id, seed)
                        .isEqualTo(quantity);

                known.add(id);
                totalSubmitted += quantity;
                takerAccounted += actual.filledQuantity() + actual.restingQuantity() + actual.cancelledQuantity();
                restingAtSubmit.put(id, actual.restingQuantity());
                for (Fill fill : actual.fills()) {
                    makerFilled += fill.quantity();
                }
            }

            // The book is never crossed.
            if (book.bestBid().isPresent() && book.bestAsk().isPresent()) {
                assertThat(book.bestBid().getAsLong())
                        .as("book crossed at action %d seed=%s", i, seed)
                        .isLessThan(book.bestAsk().getAsLong());
            }
            assertThat(book.snapshot()).as("snapshot after action %d seed=%s", i, seed)
                    .isEqualTo(reference.snapshot());
        }

        // Maker-side conservation: everything that ever rested was either filled while resting,
        // cancelled later, or is still on the book.
        BookSnapshot snapshot = book.snapshot();
        long stillResting = snapshot.bids().stream().mapToLong(BookSnapshot.Level::quantity).sum()
                + snapshot.asks().stream().mapToLong(BookSnapshot.Level::quantity).sum();
        long everRested = restingAtSubmit.values().stream().mapToLong(Integer::intValue).sum();
        assertThat(takerAccounted).isEqualTo(totalSubmitted);
        assertThat(makerFilled + laterCancelled + stillResting).isEqualTo(everRested);
    }

    @Property(tries = 100)
    void takerNeverTradesWorseThanItsLimit(@ForAll @IntRange(min = 0, max = 100_000) int seed) {
        Random random = new Random(seed);
        OrderBook book = new OrderBook();
        for (int i = 0; i < 120; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 90 + random.nextInt(21);
            MatchResult result = book.submit(NewOrder.limit("p" + i, side, price, 1 + random.nextInt(30)));
            for (Fill fill : result.fills()) {
                if (side == Side.BUY) {
                    assertThat(fill.priceCents()).as("seed=%s", seed).isLessThanOrEqualTo(price);
                } else {
                    assertThat(fill.priceCents()).as("seed=%s", seed).isGreaterThanOrEqualTo(price);
                }
            }
        }
    }
}
