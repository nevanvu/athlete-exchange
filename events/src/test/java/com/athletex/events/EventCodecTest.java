package com.athletex.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EventCodecTest {

    private final EventCodec codec = new EventCodec();
    private final Instant at = Instant.parse("2026-11-11T01:14:22.310Z");

    private EventEnvelope envelope(EventPayload payload) {
        return EventEnvelope.create("01JCZX8K4R9QW2M5T7N3VB6YH8", "simulator", "game-1", "slate-1", 42, at, payload);
    }

    @Test
    void roundTripsEveryPayloadType() {
        List<EventPayload> payloads = List.of(
                new EventPayload.GameStatus(EventPayload.GameState.IN_PROGRESS, 2, 341),
                new EventPayload.StatLine("ath-1", Map.of("pts", 18, "reb", 5, "tov", 2), 19.5, false),
                new EventPayload.Injury("ath-1", EventPayload.InjuryStatus.OUT, "ankle", false),
                new EventPayload.News("ath-1", "Trade rumor", -0.08, 0.4));
        for (EventPayload payload : payloads) {
            EventEnvelope original = envelope(payload);
            EventEnvelope decoded = codec.decode(codec.encode(original));
            assertThat(decoded).as(payload.type().toString()).isEqualTo(original);
            assertThat(decoded.type()).isEqualTo(payload.type());
        }
    }

    @Test
    void usesTheDocumentedWireFormat() {
        String json = codec.encode(envelope(new EventPayload.StatLine("ath-1", Map.of("pts", 18), 19.5, true)));
        assertThat(json)
                .contains("\"event_id\":\"01JCZX8K4R9QW2M5T7N3VB6YH8\"")
                .contains("\"type\":\"stat_line\"")
                .contains("\"schema_version\":1")
                .contains("\"occurred_at\":\"2026-11-11T01:14:22.310Z\"")
                .contains("\"athlete_id\":\"ath-1\"")
                .contains("\"minutes_played\":19.5")
                .contains("\"is_final\":true")
                .contains("\"corrects\":null");
        assertThat(json).doesNotContain("eventId").doesNotContain("schemaVersion");
    }

    @Test
    void decodesHandWrittenJson() {
        String json = """
                {"event_id":"01JCZX8K4R9QW2M5T7N3VB6YH9","type":"injury_event","schema_version":1,
                 "source":"simulator","game_id":"g","slate_id":"s","sequence":7,
                 "occurred_at":"2026-11-11T01:14:22Z","ingested_at":null,"corrects":"01JCZX8K4R9QW2M5T7N3VB6YH8",
                 "payload":{"athlete_id":"a","status":"OUT","detail":"ankle","returning_this_game":false}}
                """;
        EventEnvelope decoded = codec.decode(json);
        assertThat(decoded.type()).isEqualTo(EventType.INJURY_EVENT);
        assertThat(decoded.corrects()).isEqualTo("01JCZX8K4R9QW2M5T7N3VB6YH8");
        assertThat(((EventPayload.Injury) decoded.payload()).status()).isEqualTo(EventPayload.InjuryStatus.OUT);
    }

    @Test
    void rejectsUnknownEventType() {
        String json = """
                {"event_id":"x","type":"halftime_show","schema_version":1,"source":"s","game_id":"g",
                 "slate_id":"s","sequence":1,"occurred_at":"2026-11-11T01:14:22Z","payload":{}}
                """;
        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void ulidsAreWellFormedAndTimeOrdered() {
        Random random = new Random(1);
        String earlier = Ulid.generate(1_700_000_000_000L, random);
        String later = Ulid.generate(1_700_000_000_001L, random);
        assertThat(Ulid.isValid(earlier)).isTrue();
        assertThat(earlier).hasSize(26);
        assertThat(earlier.compareTo(later)).isLessThan(0);
        assertThat(Ulid.generate(1_700_000_000_000L, new Random(5)))
                .isEqualTo(Ulid.generate(1_700_000_000_000L, new Random(5)));
        assertThat(Ulid.isValid(Ulid.generate())).isTrue();
    }
}
