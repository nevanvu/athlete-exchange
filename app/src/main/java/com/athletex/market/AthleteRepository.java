package com.athletex.market;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteRepository extends JpaRepository<AthleteEntity, UUID> {

    List<AthleteEntity> findByTeamId(UUID teamId);
}
