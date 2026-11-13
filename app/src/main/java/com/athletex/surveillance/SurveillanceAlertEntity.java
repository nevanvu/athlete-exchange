package com.athletex.surveillance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "surveillance_alerts")
public class SurveillanceAlertEntity {

    public enum Kind {
        /** A user's order reached their own resting order; the engine stopped it. */
        SELF_TRADE_BLOCKED,
        /** Two users repeatedly trading the same athlete back and forth with little net change. */
        ROUND_TRIP
    }

    public enum Status {
        OPEN, DISMISSED
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "athlete_market_id", nullable = false)
    private UUID marketId;

    @Column(name = "user_a", nullable = false)
    private UUID userA;

    @Column(name = "user_b", nullable = false)
    private UUID userB;

    @Column(nullable = false)
    private int occurrences;

    @Column(nullable = false)
    private String detail;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected SurveillanceAlertEntity() {
    }

    public SurveillanceAlertEntity(Kind kind, UUID marketId, UUID userA, UUID userB, Instant at) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.marketId = marketId;
        this.userA = userA;
        this.userB = userB;
        this.occurrences = 0;
        this.detail = "";
        this.firstSeenAt = at;
        this.lastSeenAt = at;
        this.status = Status.OPEN;
    }

    void update(int occurrences, String detail, Instant firstSeenAt, Instant lastSeenAt) {
        // A dismissed alert reopens only if the behaviour continued after it was reviewed.
        if (status == Status.DISMISSED && lastSeenAt.isAfter(this.lastSeenAt)) {
            status = Status.OPEN;
        }
        this.occurrences = occurrences;
        this.detail = detail;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    void dismiss() {
        this.status = Status.DISMISSED;
    }

    public UUID getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public UUID getUserA() {
        return userA;
    }

    public UUID getUserB() {
        return userB;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Status getStatus() {
        return status;
    }
}
