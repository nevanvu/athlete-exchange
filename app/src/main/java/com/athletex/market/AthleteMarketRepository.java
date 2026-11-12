package com.athletex.market;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteMarketRepository extends JpaRepository<AthleteMarketEntity, UUID> {

    List<AthleteMarketEntity> findBySlateId(UUID slateId);

    long countBySlateId(UUID slateId);

    List<AthleteMarketEntity> findByGameId(UUID gameId);

    Optional<AthleteMarketEntity> findByGameIdAndAthleteId(UUID gameId, UUID athleteId);
}
