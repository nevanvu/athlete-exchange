package com.athletex.outbox;

import com.athletex.market.SlateLifecycleHooks;
import com.athletex.market.SlateStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** First producer on the market-events topic: slate transitions, via the outbox. */
@Component
public class MarketEventsPublisher implements SlateLifecycleHooks {

    public record SlateTransitioned(String type, UUID slateId, SlateStatus from, SlateStatus to,
                                    String trigger, Instant occurredAt) {
    }

    private final OutboxService outbox;
    private final String topic;

    public MarketEventsPublisher(OutboxService outbox,
                                 @Value("${athletex.kafka.market-events-topic:market-events}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Override
    public void onTransition(UUID slateId, SlateStatus from, SlateStatus to, String trigger) {
        outbox.enqueue(topic, slateId.toString(),
                new SlateTransitioned("slate_transitioned", slateId, from, to, trigger, Instant.now()));
    }
}
