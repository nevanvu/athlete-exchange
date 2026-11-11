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
@Table(name = "slates")
public class SlateEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlateStatus status;

    @Column(name = "ipo_opens_at", nullable = false)
    private Instant ipoOpensAt;

    @Column(name = "ipo_closes_at", nullable = false)
    private Instant ipoClosesAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SlateEntity() {
    }

    public SlateEntity(String name, Instant ipoOpensAt, Instant ipoClosesAt) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.status = SlateStatus.SCHEDULED;
        this.ipoOpensAt = ipoOpensAt;
        this.ipoClosesAt = ipoClosesAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SlateStatus getStatus() {
        return status;
    }

    void setStatus(SlateStatus status) {
        this.status = status;
    }

    public Instant getIpoOpensAt() {
        return ipoOpensAt;
    }

    public Instant getIpoClosesAt() {
        return ipoClosesAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
