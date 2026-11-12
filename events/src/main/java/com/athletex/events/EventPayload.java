package com.athletex.events;

import java.util.Map;

/** Type-specific body of an event. The envelope's "type" field selects the concrete record. */
public sealed interface EventPayload
        permits EventPayload.GameStatus, EventPayload.StatLine, EventPayload.Injury, EventPayload.News {

    EventType type();

    enum GameState {
        SCHEDULED, IN_PROGRESS, HALFTIME, FINAL, POSTPONED, CANCELLED
    }

    enum InjuryStatus {
        ACTIVE, QUESTIONABLE, DOUBTFUL, OUT
    }

    /** Game clock / phase change. Drives slate closing and minutes-remaining estimates. */
    record GameStatus(GameState status, int period, int clockSecondsRemaining) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.GAME_STATUS;
        }
    }

    /**
     * Cumulative stat line for one athlete (not a delta): applying the same line twice is a no-op,
     * and a correction simply replaces it.
     */
    record StatLine(String athleteId, Map<String, Integer> stats, double minutesPlayed, boolean isFinal)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.STAT_LINE;
        }
    }

    record Injury(String athleteId, InjuryStatus status, String detail, boolean returningThisGame)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.INJURY_EVENT;
        }
    }

    /** Sentiment-only signal: feeds the decaying news factor, never settlement. */
    record News(String athleteId, String headline, double impact, double confidence) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.NEWS_EVENT;
        }
    }
}
