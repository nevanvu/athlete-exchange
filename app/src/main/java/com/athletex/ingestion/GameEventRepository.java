package com.athletex.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameEventRepository extends JpaRepository<GameEventEntity, String> {

    List<GameEventEntity> findByGameIdOrderBySequenceAsc(UUID gameId);

    @Query("select max(e.sequence) from GameEventEntity e where e.gameId = :gameId")
    Optional<Long> maxSequence(@Param("gameId") UUID gameId);

    long countByGameId(UUID gameId);
}
