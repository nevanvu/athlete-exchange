package com.athletex.ingestion;

import com.athletex.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Front door for incoming events (Kafka listener, replay harness): reorders per game, then
 * ingests ready events. Reordering and ingestion happen under one lock so the listener thread
 * and the gap-flush timer can never apply a game's events in a different order than released.
 */
@Component
public class GameEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(GameEventProcessor.class);

    private final EventIngestionService ingestion;
    private final GameEventSequencer sequencer;

    public GameEventProcessor(EventIngestionService ingestion,
                              GameEventRepository eventRepository,
                              @Value("${athletex.ingestion.reorder-max-wait-ms:2000}") long reorderMaxWaitMs) {
        this.ingestion = ingestion;
        this.sequencer = new GameEventSequencer(
                gameId -> eventRepository.maxSequence(UUID.fromString(gameId)).map(max -> max + 1).orElse(1L),
                Duration.ofMillis(reorderMaxWaitMs));
    }

    public synchronized void process(EventEnvelope event) {
        ingestAll(sequencer.offer(event, Instant.now()));
    }

    @Scheduled(fixedDelayString = "${athletex.ingestion.reorder-flush-ms:1000}")
    public synchronized void flushStale() {
        List<EventEnvelope> ready = sequencer.flushStale(Instant.now());
        if (!ready.isEmpty()) {
            log.warn("sequence gap timed out; releasing {} buffered event(s)", ready.size());
            ingestAll(ready);
        }
    }

    public int bufferedCount() {
        return sequencer.bufferedCount();
    }

    private void ingestAll(List<EventEnvelope> events) {
        for (EventEnvelope event : events) {
            try {
                ingestion.ingest(event);
            } catch (IllegalArgumentException e) {
                log.warn("dropping event {}: {}", event.eventId(), e.getMessage());
            }
        }
    }
}
