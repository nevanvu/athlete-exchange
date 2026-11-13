package com.athletex.surveillance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveillanceAlertRepository extends JpaRepository<SurveillanceAlertEntity, UUID> {

    Optional<SurveillanceAlertEntity> findByKindAndMarketIdAndUserAAndUserB(
            SurveillanceAlertEntity.Kind kind, UUID marketId, UUID userA, UUID userB);

    List<SurveillanceAlertEntity> findByStatusOrderByLastSeenAtDesc(SurveillanceAlertEntity.Status status);

    List<SurveillanceAlertEntity> findAllByOrderByLastSeenAtDesc();
}
