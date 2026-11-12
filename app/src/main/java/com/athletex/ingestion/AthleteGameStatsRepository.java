package com.athletex.ingestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteGameStatsRepository extends JpaRepository<AthleteGameStatsEntity, GameAthleteKey> {

    List<AthleteGameStatsEntity> findByKeyGameIdOrderByKeyAthleteId(UUID gameId);
}
