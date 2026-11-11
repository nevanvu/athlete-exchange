package com.athletex.trading;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    List<OrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);

    List<OrderEntity> findByStatusInOrderByCreatedAtAsc(Collection<OrderStatus> statuses);

    List<OrderEntity> findByMarketIdAndStatusInOrderByCreatedAtAsc(UUID marketId, Collection<OrderStatus> statuses);
}
