package com.athletex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.Await;
import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload.GameState;
import com.athletex.market.SlateStatus;
import com.athletex.outbox.OutboxEntity;
import com.athletex.outbox.OutboxRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/** Real broker end to end: events published to game-events are consumed and applied; the outbox relays. */
@SpringBootTest(properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false",
        "athletex.outbox.relay.delay-ms=200"
})
class KafkaPipelineTest extends AbstractIngestionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private OutboxRepository outboxRepository;

    private final EventCodec codec = new EventCodec();

    @Test
    void eventsPublishedToKafkaAreIngestedInOrderAndCloseTheSlate() {
        Fixture f = new Fixture(true);
        List<EventEnvelope> events = List.of(
                f.next(f.status(GameState.IN_PROGRESS, 1, 720)),
                f.next(f.line(f.star, 8, 2, 1, 6.0, false)),
                f.next(f.line(f.star, 15, 4, 3, 14.0, false)),
                f.next(f.line(f.star, 31, 7, 5, 34.0, true)),
                f.next(f.line(f.bench, 6, 3, 0, 12.0, true)),
                f.next(f.status(GameState.FINAL, 4, 0)));

        for (EventEnvelope event : events) {
            kafkaTemplate.send("game-events", event.gameId(), codec.encode(event));
        }
        // Redelivery of the whole game must be harmless.
        for (EventEnvelope event : events) {
            kafkaTemplate.send("game-events", event.gameId(), codec.encode(event));
        }

        Await.until(TIMEOUT, "slate to close from the FINAL event", () -> f.slateStatus() == SlateStatus.CLOSED);
        Await.until(TIMEOUT, "all events stored once", () -> eventRepository.countByGameId(f.gameId) == events.size());

        AthleteGameStatsEntity star = f.stats(f.star);
        assertThat(star.isFinal()).isTrue();
        assertThat(star.getFantasyPoints()).isEqualByComparingTo("46.90"); // 31 + 7*1.2 + 5*1.5
        assertThat(f.game().getStatusSequence()).isEqualTo(6);
    }

    @Test
    void slateTransitionsAreRelayedFromTheOutbox() {
        Fixture f = new Fixture(true);
        List<OutboxEntity> rows = outboxRepository.findByMessageKeyOrderByCreatedAtAsc(f.slateId.toString());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPayload()).contains("\"to\":\"IPO\"");
        assertThat(rows.get(1).getPayload()).contains("\"to\":\"OPEN_MARKET\"");

        Await.until(TIMEOUT, "outbox rows to be published", () ->
                outboxRepository.findByMessageKeyOrderByCreatedAtAsc(f.slateId.toString()).stream()
                        .allMatch(row -> row.getPublishedAt() != null));
    }
}
