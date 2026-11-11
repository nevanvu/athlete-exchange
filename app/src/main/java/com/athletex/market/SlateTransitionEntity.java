package com.athletex.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slate_transitions")
public class SlateTransitionEntity {

    @Id
    private UUID id;

    @Column(name = "slate_id", nullable = false)
    private UUID slateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false)
    private SlateStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private SlateStatus toStatus;

    @Column(name = "trigger", nullable = false)
    private String trigger;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SlateTransitionEntity() {
    }

    public SlateTransitionEntity(UUID slateId, SlateStatus fromStatus, SlateStatus toStatus, String trigger) {
        this.id = UUID.randomUUID();
        this.slateId = slateId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.trigger = trigger;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public SlateStatus getFromStatus() {
        return fromStatus;
    }

    public SlateStatus getToStatus() {
        return toStatus;
    }

    public String getTrigger() {
        return trigger;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
