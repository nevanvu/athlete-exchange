package com.athletex.settlement;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementCorrectionRepository extends JpaRepository<SettlementCorrectionEntity, UUID> {

    boolean existsByEventId(String eventId);

    List<SettlementCorrectionEntity> findByMarketIdInOrderByCreatedAtAsc(List<UUID> marketIds);
}
