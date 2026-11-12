package com.athletex.ingestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteAvailabilityRepository extends JpaRepository<AthleteAvailabilityEntity, GameAthleteKey> {

    List<AthleteAvailabilityEntity> findByKeyGameId(UUID gameId);
}
