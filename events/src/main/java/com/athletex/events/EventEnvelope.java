package com.athletex.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/**
 * Wire envelope per docs/event-schema.md. {@code eventId} is the idempotency key;
 * {@code sequence} is monotonic per game from the source; {@code corrects} names an earlier
 * event this one fully replaces.
 */
public record EventEnvelope(
        String eventId,
        int schemaVersion,
        String source,
        String gameId,
        String slateId,
        long sequence,
        Instant occurredAt,
        Instant ingestedAt,
        String corrects,
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type")
        @JsonSubTypes({
                @JsonSubTypes.Type(value = EventPayload.GameStatus.class, name = "game_status"),
                @JsonSubTypes.Type(value = EventPayload.StatLine.class, name = "stat_line"),
                @JsonSubTypes.Type(value = EventPayload.Injury.class, name = "injury_event"),
                @JsonSubTypes.Type(value = EventPayload.News.class, name = "news_event")
        })
        EventPayload payload) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static EventEnvelope create(String eventId, String source, String gameId, String slateId,
                                       long sequence, Instant occurredAt, EventPayload payload) {
        return new EventEnvelope(eventId, CURRENT_SCHEMA_VERSION, source, gameId, slateId, sequence,
                occurredAt, null, null, payload);
    }

    @JsonIgnore
    public EventType type() {
        return payload.type();
    }

    public EventEnvelope withIngestedAt(Instant at) {
        return new EventEnvelope(eventId, schemaVersion, source, gameId, slateId, sequence, occurredAt, at, corrects, payload);
    }

    public EventEnvelope correcting(String correctedEventId, String newEventId, long newSequence, EventPayload newPayload) {
        return new EventEnvelope(newEventId, schemaVersion, source, gameId, slateId, newSequence, occurredAt, null,
                correctedEventId, newPayload);
    }
}
