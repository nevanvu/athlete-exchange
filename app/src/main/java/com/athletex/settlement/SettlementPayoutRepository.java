package com.athletex.settlement;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementPayoutRepository extends JpaRepository<SettlementPayoutEntity, UUID> {

    List<SettlementPayoutEntity> findByMarketId(UUID marketId);

    List<SettlementPayoutEntity> findByRunId(UUID runId);
}
