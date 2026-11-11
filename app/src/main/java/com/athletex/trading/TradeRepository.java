package com.athletex.trading;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, UUID> {

    List<TradeEntity> findTop50ByMarketIdOrderByExecutedAtDesc(UUID marketId);
}
