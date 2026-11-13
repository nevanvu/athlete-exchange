package com.athletex.social;

import com.athletex.market.AthleteEntity;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteRepository;
import com.athletex.market.SlateEntity;
import com.athletex.outbox.OutboxService;
import com.athletex.trading.PositionEntity;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notable moves, written as the events happen (inside their transactions) and streamed to
 * /topic/feed: halts, big trades, IPO results and payouts. Traders are named only if their
 * portfolio is public.
 */
@Service
public class FeedService {

    public record FeedItemPublished(String type, UUID id, String kind, UUID slateId, UUID athleteMarketId,
                                    String headline, String detail, Instant occurredAt) {
    }

    private final FeedItemRepository repository;
    private final AthleteRepository athleteRepository;
    private final TraderNames names;
    private final OutboxService outbox;
    private final String topic;
    private final long bigTradeCents;

    public FeedService(FeedItemRepository repository, AthleteRepository athleteRepository, TraderNames names,
                       OutboxService outbox,
                       @Value("${athletex.kafka.market-events-topic:market-events}") String topic,
                       @Value("${athletex.feed.big-trade-cents:100000}") long bigTradeCents) {
        this.repository = repository;
        this.athleteRepository = athleteRepository;
        this.names = names;
        this.outbox = outbox;
        this.topic = topic;
        this.bigTradeCents = bigTradeCents;
    }

    public static String money(long cents) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(cents / 100.0);
    }

    @Transactional
    public void halted(AthleteMarketEntity market, String reason, Long before, Long after) {
        String move = before != null && after != null ? " Fair value " + money(before) + " to " + money(after) + "." : "";
        record("HALT", market.getSlateId(), market.getId(),
                athlete(market) + " halted: " + reason + ".", "Trading reopens in 30 seconds." + move);
    }

    /** Big trades only, and never the house trading with itself. */
    @Transactional
    public void traded(AthleteMarketEntity market, UUID takerUserId, boolean takerBought, long priceCents, int quantity) {
        if (priceCents * quantity < bigTradeCents || PositionEntity.HOUSE_USER_ID.equals(takerUserId)) {
            return;
        }
        record("BIG_TRADE", market.getSlateId(), market.getId(),
                names.publicName(takerUserId) + (takerBought ? " bought " : " sold ") + quantity + " shares of "
                        + athlete(market) + " at " + money(priceCents) + ".",
                money(priceCents * quantity) + " in one order.");
    }

    @Transactional
    public void ipoCleared(SlateEntity slate, int sharesSold, int markets, String topAthlete, long topPriceCents) {
        record("IPO", slate.getId(), null, "The " + slate.getName() + " IPO cleared: " + sharesSold
                        + " shares sold across " + markets + (markets == 1 ? " athlete." : " athletes."),
                topAthlete == null ? null : "Highest clearing price: " + topAthlete + " at " + money(topPriceCents) + ".");
    }

    @Transactional
    public void settled(SlateEntity slate, long totalPaidCents, String topAthlete, long topValueCents) {
        record("PAYOUT", slate.getId(), null, slate.getName() + " paid out " + money(totalPaidCents) + ".",
                topAthlete == null ? null : "Top payout: " + topAthlete + " at " + money(topValueCents) + " a share.");
    }

    public String athlete(AthleteMarketEntity market) {
        return athleteRepository.findById(market.getAthleteId()).map(AthleteEntity::getName).orElse("An athlete");
    }

    private void record(String kind, UUID slateId, UUID marketId, String headline, String detail) {
        FeedItemEntity item = repository.save(new FeedItemEntity(kind, slateId, marketId, headline, detail, Instant.now()));
        outbox.enqueue(topic, "feed", new FeedItemPublished("feed_item", item.getId(), kind, slateId, marketId,
                headline, detail, item.getOccurredAt()));
    }
}
