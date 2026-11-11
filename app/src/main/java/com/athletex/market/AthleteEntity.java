package com.athletex.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "athletes")
public class AthleteEntity {

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String position;

    protected AthleteEntity() {
    }

    public AthleteEntity(UUID teamId, String name, String position) {
        this.id = UUID.randomUUID();
        this.teamId = teamId;
        this.name = name;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public String getPosition() {
        return position;
    }
}
