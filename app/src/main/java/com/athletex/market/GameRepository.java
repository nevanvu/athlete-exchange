package com.athletex.market;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    List<GameEntity> findBySlateId(UUID slateId);

    long countBySlateId(UUID slateId);

    long countBySlateIdAndStatusNot(UUID slateId, GameStatus status);
}
