package com.athletex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameEventSequencerTest {

    private static final Instant T0 = Instant.parse("2026-11-11T00:00:00Z");

    private final GameEventSequencer sequencer = new GameEventSequencer(gameId -> 1L, Duration.ofSeconds(2));

    private static EventEnvelope event(String gameId, long sequence) {
        return EventEnvelope.create("e-" + gameId + "-" + sequence, "test", gameId, "s", sequence, T0,
                new EventPayload.GameStatus(EventPayload.GameState.IN_PROGRESS, 1, 700));
    }

    private static List<Long> sequences(List<EventEnvelope> events) {
        return events.stream().map(EventEnvelope::sequence).toList();
    }

    @Test
    void inOrderEventsPassStraightThrough() {
        assertThat(sequences(sequencer.offer(event("g", 1), T0))).containsExactly(1L);
        assertThat(sequences(sequencer.offer(event("g", 2), T0))).containsExactly(2L);
        assertThat(sequencer.bufferedCount()).isZero();
    }

    @Test
    void gapIsHeldUntilFilledThenReleasedInOrder() {
        assertThat(sequencer.offer(event("g", 3), T0)).isEmpty();
        assertThat(sequencer.offer(event("g", 2), T0)).isEmpty();
        assertThat(sequencer.bufferedCount()).isEqualTo(2);
        assertThat(sequences(sequencer.offer(event("g", 1), T0))).containsExactly(1L, 2L, 3L);
        assertThat(sequencer.bufferedCount()).isZero();
    }

    @Test
    void staleGapIsSkippedAfterMaxWait() {
        sequencer.offer(event("g", 1), T0);
        sequencer.offer(event("g", 3), T0);
        sequencer.offer(event("g", 4), T0);
        assertThat(sequencer.flushStale(T0.plusSeconds(1))).isEmpty();
        assertThat(sequences(sequencer.flushStale(T0.plusSeconds(2)))).containsExactly(3L, 4L);
        // The missing event finally shows up: it is behind the cursor and passes through untouched.
        assertThat(sequences(sequencer.offer(event("g", 2), T0.plusSeconds(3)))).containsExactly(2L);
        assertThat(sequences(sequencer.offer(event("g", 5), T0.plusSeconds(3)))).containsExactly(5L);
    }

    @Test
    void duplicatesBehindTheCursorPassThroughForIdempotentHandling() {
        sequencer.offer(event("g", 1), T0);
        sequencer.offer(event("g", 2), T0);
        assertThat(sequences(sequencer.offer(event("g", 1), T0))).containsExactly(1L);
    }

    @Test
    void gamesAreSequencedIndependently() {
        assertThat(sequencer.offer(event("a", 2), T0)).isEmpty();
        assertThat(sequences(sequencer.offer(event("b", 1), T0))).containsExactly(1L);
        assertThat(sequences(sequencer.offer(event("a", 1), T0))).containsExactly(1L, 2L);
    }

    @Test
    void cursorStartsAfterWhatIsAlreadyStored() {
        GameEventSequencer resumed = new GameEventSequencer(gameId -> 41L, Duration.ofSeconds(2));
        assertThat(sequences(resumed.offer(event("g", 41), T0))).containsExactly(41L);
        assertThat(sequences(resumed.offer(event("g", 7), T0))).containsExactly(7L);
    }
}
