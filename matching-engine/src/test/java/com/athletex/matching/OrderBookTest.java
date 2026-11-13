package com.athletex.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final OrderBook book = new OrderBook();

    @Test
    void restingOrderDoesNotTradeUntilCrossed() {
        MatchResult result = book.submit(NewOrder.limit("b1", Side.BUY, 100, 10));
        assertThat(result.fills()).isEmpty();
        assertThat(result.restingQuantity()).isEqualTo(10);
        assertThat(book.bestBid()).hasValue(100);
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void takerFillsAtMakerPriceWithPriceImprovement() {
        book.submit(NewOrder.limit("s1", Side.SELL, 101, 5));
        MatchResult result = book.submit(NewOrder.limit("b1", Side.BUY, 105, 5));
        // Buyer was willing to pay 105 but trades at the resting 101.
        assertThat(result.fills()).containsExactly(new Fill("b1", "s1", 101, 5));
        assertThat(result.fullyFilled()).isTrue();
    }

    @Test
    void pricePriorityBeatsTimePriority() {
        book.submit(NewOrder.limit("s_late_cheap", Side.SELL, 100, 5));
        book.submit(NewOrder.limit("s_early_expensive", Side.SELL, 102, 5));
        MatchResult result = book.submit(NewOrder.market("b1", Side.BUY, 5));
        assertThat(result.fills()).containsExactly(new Fill("b1", "s_late_cheap", 100, 5));
    }

    @Test
    void timePriorityWithinSamePriceLevel() {
        book.submit(NewOrder.limit("s_first", Side.SELL, 100, 5));
        book.submit(NewOrder.limit("s_second", Side.SELL, 100, 5));
        MatchResult result = book.submit(NewOrder.limit("b1", Side.BUY, 100, 7));
        assertThat(result.fills()).containsExactly(
                new Fill("b1", "s_first", 100, 5),
                new Fill("b1", "s_second", 100, 2));
        assertThat(result.fullyFilled()).isTrue();
    }

    @Test
    void partialFillRestsTheRemainder() {
        book.submit(NewOrder.limit("s1", Side.SELL, 100, 3));
        MatchResult result = book.submit(NewOrder.limit("b1", Side.BUY, 100, 10));
        assertThat(result.filledQuantity()).isEqualTo(3);
        assertThat(result.restingQuantity()).isEqualTo(7);
        assertThat(book.bestBid()).hasValue(100);
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void marketOrderSweepsLevelsAndCancelsRemainder() {
        book.submit(NewOrder.limit("s1", Side.SELL, 100, 4));
        book.submit(NewOrder.limit("s2", Side.SELL, 103, 4));
        MatchResult result = book.submit(NewOrder.market("b1", Side.BUY, 10));
        assertThat(result.fills()).containsExactly(
                new Fill("b1", "s1", 100, 4),
                new Fill("b1", "s2", 103, 4));
        assertThat(result.restingQuantity()).isZero();
        assertThat(result.cancelledQuantity()).isEqualTo(2);
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void marketOrderAgainstEmptyBookIsFullyCancelled() {
        MatchResult result = book.submit(NewOrder.market("b1", Side.BUY, 5));
        assertThat(result.fills()).isEmpty();
        assertThat(result.cancelledQuantity()).isEqualTo(5);
    }

    @Test
    void cancelRemovesRestingQuantityAndLevel() {
        book.submit(NewOrder.limit("b1", Side.BUY, 100, 10));
        assertThat(book.cancel("b1")).isEqualTo(10);
        assertThat(book.bestBid()).isEmpty();
        assertThat(book.cancel("b1")).isZero();
        assertThat(book.cancel("nope")).isZero();
    }

    @Test
    void cancelledOrderNeverTrades() {
        book.submit(NewOrder.limit("s1", Side.SELL, 100, 5));
        book.submit(NewOrder.limit("s2", Side.SELL, 100, 5));
        book.cancel("s1");
        MatchResult result = book.submit(NewOrder.limit("b1", Side.BUY, 100, 5));
        assertThat(result.fills()).containsExactly(new Fill("b1", "s2", 100, 5));
    }

    @Test
    void snapshotAggregatesByLevelBestFirst() {
        book.submit(NewOrder.limit("b1", Side.BUY, 99, 5));
        book.submit(NewOrder.limit("b2", Side.BUY, 100, 3));
        book.submit(NewOrder.limit("b3", Side.BUY, 100, 2));
        book.submit(NewOrder.limit("s1", Side.SELL, 101, 4));
        BookSnapshot snapshot = book.snapshot();
        assertThat(snapshot.bids()).containsExactly(
                new BookSnapshot.Level(100, 5),
                new BookSnapshot.Level(99, 5));
        assertThat(snapshot.asks()).containsExactly(new BookSnapshot.Level(101, 4));
    }

    @Test
    void rejectsInvalidOrders() {
        assertThatThrownBy(() -> book.submit(NewOrder.limit("x", Side.BUY, 0, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book.submit(NewOrder.limit("x", Side.BUY, 100, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book.submit(NewOrder.limit(" ", Side.BUY, 100, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        book.submit(NewOrder.limit("dup", Side.BUY, 100, 5));
        assertThatThrownBy(() -> book.submit(NewOrder.limit("dup", Side.BUY, 100, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void fullyFilledTakerLeavesNoTrace() {
        book.submit(NewOrder.limit("s1", Side.SELL, 100, 5));
        book.submit(NewOrder.limit("b1", Side.BUY, 100, 5));
        assertThat(book.cancel("b1")).isZero();
        assertThat(book.cancel("s1")).isZero();
        assertThat(book.snapshot().bids()).isEmpty();
        assertThat(book.snapshot().asks()).isEmpty();
    }

    @Test
    void reusedIdOfFullyFilledOrderIsAllowed() {
        // Only resting ids must be unique; a filled order's id leaves the index.
        book.submit(NewOrder.limit("s1", Side.SELL, 100, 5));
        book.submit(NewOrder.limit("o1", Side.BUY, 100, 5));
        MatchResult result = book.submit(NewOrder.limit("o1", Side.BUY, 90, 5));
        assertThat(result.restingQuantity()).isEqualTo(5);
        assertThat(List.of(result.orderId())).containsExactly("o1");
    }

    @Test
    void selfTradePreventionStopsAtTheOwnersOwnOrder() {
        book.submit(NewOrder.limit("other", Side.SELL, 100, 5).ownedBy("bob"));
        book.submit(NewOrder.limit("mine", Side.SELL, 101, 5).ownedBy("alice"));
        book.submit(NewOrder.limit("other2", Side.SELL, 102, 5).ownedBy("bob"));

        MatchResult result = book.submit(NewOrder.limit("buy", Side.BUY, 105, 15).ownedBy("alice"));

        // Fills against bob's better-priced order stand; matching stops at alice's own ask.
        assertThat(result.fills()).containsExactly(new Fill("buy", "other", 100, 5));
        assertThat(result.selfTradePrevented()).isTrue();
        assertThat(result.restingQuantity()).as("never rests across its own order").isZero();
        assertThat(result.cancelledQuantity()).isEqualTo(10);
        assertThat(book.bestAsk()).hasValue(101);
        assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void ordersWithoutAnOwnerAreNotRestricted() {
        book.submit(NewOrder.limit("s", Side.SELL, 100, 5));
        MatchResult result = book.submit(NewOrder.limit("b", Side.BUY, 100, 5));
        assertThat(result.fullyFilled()).isTrue();
        assertThat(result.selfTradePrevented()).isFalse();
    }
}
