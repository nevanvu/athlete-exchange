package com.athletex.settlement;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRunRepository extends JpaRepository<SettlementRunEntity, UUID> {

    Optional<SettlementRunEntity> findBySlateId(UUID slateId);

    boolean existsBySlateId(UUID slateId);
}
