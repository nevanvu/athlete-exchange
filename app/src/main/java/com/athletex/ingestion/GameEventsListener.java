package com.athletex.ingestion;

import com.athletex.events.EventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "athletex.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class GameEventsListener {

    private static final Logger log = LoggerFactory.getLogger(GameEventsListener.class);

    private final GameEventProcessor processor;
    private final EventCodec codec = new EventCodec();

    public GameEventsListener(GameEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = "${athletex.kafka.game-events-topic}")
    public void onMessage(String message) {
        try {
            processor.process(codec.decode(message));
        } catch (RuntimeException e) {
            // A poison message must not stall the partition; it is logged and skipped.
            log.error("could not process game event: {}", e.getMessage());
        }
    }
}
