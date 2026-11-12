package com.athletex.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * Published synchronously, inside the ingestion transaction, whenever an event changes game
 * state. Listeners (fair value, settlement corrections) therefore commit or roll back together
 * with the change that triggered them.
 */
public sealed interface GameDataChanged {

    UUID gameId();

    String eventId();

    Instant occurredAt();

    record GameClock(UUID gameId, String eventId, Instant occurredAt) implements GameDataChanged {
    }

    record AthleteStats(UUID gameId, UUID athleteId, String eventId, Instant occurredAt, boolean isFinal)
            implements GameDataChanged {
    }

    record AthleteAvailability(UUID gameId, UUID athleteId, String eventId, Instant occurredAt)
            implements GameDataChanged {
    }

    record AthleteNews(UUID gameId, UUID athleteId, String eventId, Instant occurredAt) implements GameDataChanged {
    }
}
