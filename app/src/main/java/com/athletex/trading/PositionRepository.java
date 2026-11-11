package com.athletex.trading;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PositionRepository extends JpaRepository<PositionEntity, UUID> {

    Optional<PositionEntity> findByUserIdAndMarketId(UUID userId, UUID marketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PositionEntity p where p.userId = :userId and p.marketId = :marketId")
    Optional<PositionEntity> findByUserIdAndMarketIdForUpdate(@Param("userId") UUID userId,
                                                              @Param("marketId") UUID marketId);

    List<PositionEntity> findByUserIdAndQuantityGreaterThan(UUID userId, int quantity);

    List<PositionEntity> findByMarketId(UUID marketId);

    @Query("select coalesce(sum(p.quantity), 0) from PositionEntity p where p.marketId = :marketId")
    long totalSharesInMarket(@Param("marketId") UUID marketId);
}
