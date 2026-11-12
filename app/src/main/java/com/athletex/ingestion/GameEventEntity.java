package com.athletex.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_events")
public class GameEventEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "slate_id")
    private UUID slateId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    private String corrects;

    @Column(nullable = false)
    private String payload;

    protected GameEventEntity() {
    }

    public GameEventEntity(String eventId, UUID gameId, UUID slateId, String type, long sequence, String source,
                           Instant occurredAt, String corrects, String payload) {
        this.eventId = eventId;
        this.gameId = gameId;
        this.slateId = slateId;
        this.type = type;
        this.sequence = sequence;
        this.source = source;
        this.occurredAt = occurredAt;
        this.ingestedAt = Instant.now();
        this.corrects = corrects;
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public String getType() {
        return type;
    }

    public long getSequence() {
        return sequence;
    }

    public String getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public String getCorrects() {
        return corrects;
    }

    public String getPayload() {
        return payload;
    }
}
