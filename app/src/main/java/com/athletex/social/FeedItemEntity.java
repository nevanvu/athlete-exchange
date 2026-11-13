package com.athletex.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feed_items")
public class FeedItemEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind;

    @Column(name = "slate_id")
    private UUID slateId;

    @Column(name = "athlete_market_id")
    private UUID marketId;

    @Column(nullable = false)
    private String headline;

    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected FeedItemEntity() {
    }

    public FeedItemEntity(String kind, UUID slateId, UUID marketId, String headline, String detail, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.slateId = slateId;
        this.marketId = marketId;
        this.headline = headline;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public UUID getSlateId() {
        return slateId;
    }

    public UUID getMarketId() {
        return marketId;
    }

    public String getHeadline() {
        return headline;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
