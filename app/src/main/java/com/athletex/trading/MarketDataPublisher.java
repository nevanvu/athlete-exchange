package com.athletex.trading;

import com.athletex.market.AthleteMarketEntity;
import com.athletex.matching.BookSnapshot;
import com.athletex.matching.OrderBook;
import com.athletex.matching.Side;
import com.athletex.outbox.OutboxService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Book and trade messages for the market-events topic, written through the outbox inside the
 * order's transaction: a rolled-back order publishes nothing, a committed one always publishes.
 */
@Component
public class MarketDataPublisher {

    static final int DEPTH = 10;

    /** Carries both user ids for private fill notifications; the stream relay strips them before broadcasting. */
    public record TradeExecuted(String type, UUID athleteMarketId, UUID slateId, UUID tradeId, long priceCents,
                                int quantity, Side takerSide, UUID buyerUserId, UUID sellerUserId,
                                Instant executedAt) {
    }

    public record BookUpdated(String type, UUID athleteMarketId, UUID slateId, List<BookSnapshot.Level> bids,
                              List<BookSnapshot.Level> asks, Long lastTradePriceCents, Instant at) {
    }

    private final OutboxService outbox;
    private final String topic;

    public MarketDataPublisher(OutboxService outbox,
                               @Value("${athletex.kafka.market-events-topic:market-events}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    void tradeExecuted(AthleteMarketEntity market, TradeEntity trade, Side takerSide) {
        outbox.enqueue(topic, market.getId().toString(), new TradeExecuted("trade_executed", market.getId(),
                market.getSlateId(), trade.getId(), trade.getPriceCents(), trade.getQuantity(), takerSide,
                trade.getBuyerUserId(), trade.getSellerUserId(), trade.getExecutedAt()));
    }

    void bookUpdated(AthleteMarketEntity market, OrderBook book) {
        BookSnapshot snapshot = book.snapshot();
        outbox.enqueue(topic, market.getId().toString(), new BookUpdated("book_updated", market.getId(),
                market.getSlateId(), top(snapshot.bids()), top(snapshot.asks()), market.getLastTradePriceCents(),
                Instant.now()));
    }

    private static List<BookSnapshot.Level> top(List<BookSnapshot.Level> levels) {
        return levels.size() <= DEPTH ? levels : levels.subList(0, DEPTH);
    }
}
