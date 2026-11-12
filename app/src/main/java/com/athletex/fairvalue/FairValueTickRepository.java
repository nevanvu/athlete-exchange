package com.athletex.fairvalue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FairValueTickRepository extends JpaRepository<FairValueTickEntity, Long> {

    List<FairValueTickEntity> findByMarketIdOrderByIdAsc(UUID marketId);

    List<FairValueTickEntity> findTop200ByMarketIdOrderByIdDesc(UUID marketId);
}
