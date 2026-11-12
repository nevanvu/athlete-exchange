package com.athletex.halts;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketHaltRepository extends JpaRepository<MarketHaltEntity, UUID> {

    List<MarketHaltEntity> findByMarketIdOrderByHaltedAtAsc(UUID marketId);

    List<MarketHaltEntity> findByMarketIdAndResumedAtIsNull(UUID marketId);
}
