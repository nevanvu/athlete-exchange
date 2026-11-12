package com.athletex.market;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AthleteMarketRepository extends JpaRepository<AthleteMarketEntity, UUID> {

    List<AthleteMarketEntity> findBySlateId(UUID slateId);

    long countBySlateId(UUID slateId);

    List<AthleteMarketEntity> findByGameId(UUID gameId);

    Optional<AthleteMarketEntity> findByGameIdAndAthleteId(UUID gameId, UUID athleteId);

    /*
     * Market row locks. Order placement takes the in-memory market lock first and this row lock
     * second; every other writer (fair value, halts, settlement) takes row locks only, never the
     * in-memory lock, so the two lock kinds can never wait on each other in a cycle.
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from AthleteMarketEntity m where m.id = :id")
    Optional<AthleteMarketEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from AthleteMarketEntity m where m.gameId = :gameId order by m.id")
    List<AthleteMarketEntity> findByGameIdForUpdate(@Param("gameId") UUID gameId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from AthleteMarketEntity m where m.gameId = :gameId and m.athleteId = :athleteId")
    Optional<AthleteMarketEntity> findByGameIdAndAthleteIdForUpdate(@Param("gameId") UUID gameId,
                                                                    @Param("athleteId") UUID athleteId);

    List<AthleteMarketEntity> findByStatusAndResumeAfterLessThanEqual(MarketStatus status, Instant now);
}
