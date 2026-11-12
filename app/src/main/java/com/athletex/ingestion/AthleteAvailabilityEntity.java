package com.athletex.ingestion;

import com.athletex.events.EventPayload.InjuryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "athlete_availability")
public class AthleteAvailabilityEntity {

    @EmbeddedId
    private GameAthleteKey key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InjuryStatus status;

    private String detail;

    @Column(name = "as_of_sequence", nullable = false)
    private long asOfSequence;

    @Column(name = "as_of_event_id", nullable = false)
    private String asOfEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AthleteAvailabilityEntity() {
    }

    public AthleteAvailabilityEntity(GameAthleteKey key) {
        this.key = key;
        this.status = InjuryStatus.ACTIVE;
        this.asOfSequence = -1;
        this.asOfEventId = "";
        this.updatedAt = Instant.now();
    }

    public GameAthleteKey getKey() {
        return key;
    }

    public InjuryStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public long getAsOfSequence() {
        return asOfSequence;
    }

    public String getAsOfEventId() {
        return asOfEventId;
    }

    void replace(InjuryStatus status, String detail, long sequence, String eventId) {
        this.status = status;
        this.detail = detail;
        this.asOfSequence = sequence;
        this.asOfEventId = eventId;
        this.updatedAt = Instant.now();
    }
}
