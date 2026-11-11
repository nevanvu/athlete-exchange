package com.athletex.market;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlateTransitionRepository extends JpaRepository<SlateTransitionEntity, UUID> {

    List<SlateTransitionEntity> findBySlateIdOrderByOccurredAt(UUID slateId);
}
