package com.athletex.trading;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpoBidRepository extends JpaRepository<IpoBidEntity, UUID> {

    List<IpoBidEntity> findByMarketIdAndStatusOrderByMaxPriceCentsDescCreatedAtAsc(UUID marketId,
                                                                                    IpoBidEntity.Status status);

    List<IpoBidEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<IpoBidEntity> findByIdAndUserId(UUID id, UUID userId);
}
