package com.athletex.ingestion;

import com.athletex.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Per-game reorder buffer. Events arriving ahead of the next expected sequence are held
 * until the gap fills; if it does not fill within {@code maxWait}, the gap is skipped.
 * Events behind the expected sequence pass straight through (ingestion handles duplicates
 * and staleness), so nothing is ever dropped here.
 */
public class GameEventSequencer {

    private record Pending(EventEnvelope event, Instant arrivedAt) {
    }

    private static final class GameState {
        long expected;
        final TreeMap<Long, Pending> buffered = new TreeMap<>();

        GameState(long expected) {
            this.expected = expected;
        }
    }

    private final Function<String, Long> initialExpectedSequence;
    private final Duration maxWait;
    private final Map<String, GameState> games = new HashMap<>();

    public GameEventSequencer(Function<String, Long> initialExpectedSequence, Duration maxWait) {
        this.initialExpectedSequence = initialExpectedSequence;
        this.maxWait = maxWait;
    }

    /** Returns the events that are now ready to process, in sequence order. */
    public synchronized List<EventEnvelope> offer(EventEnvelope event, Instant now) {
        GameState game = games.computeIfAbsent(event.gameId(), id -> new GameState(initialExpectedSequence.apply(id)));
        if (event.sequence() < game.expected) {
            return List.of(event);
        }
        if (event.sequence() > game.expected) {
            game.buffered.putIfAbsent(event.sequence(), new Pending(event, now));
            return List.of();
        }
        List<EventEnvelope> ready = new ArrayList<>();
        ready.add(event);
        game.expected++;
        drain(game, ready);
        return ready;
    }

    /** Releases buffered events whose gap has waited longer than {@code maxWait}. */
    public synchronized List<EventEnvelope> flushStale(Instant now) {
        List<EventEnvelope> ready = new ArrayList<>();
        for (GameState game : games.values()) {
            while (!game.buffered.isEmpty()
                    && Duration.between(game.buffered.firstEntry().getValue().arrivedAt(), now).compareTo(maxWait) >= 0) {
                game.expected = game.buffered.firstKey();
                drain(game, ready);
            }
        }
        return ready;
    }

    public synchronized int bufferedCount() {
        return games.values().stream().mapToInt(g -> g.buffered.size()).sum();
    }

    private static void drain(GameState game, List<EventEnvelope> into) {
        while (!game.buffered.isEmpty() && game.buffered.firstKey() == game.expected) {
            into.add(game.buffered.pollFirstEntry().getValue().event());
            game.expected++;
        }
    }
}
