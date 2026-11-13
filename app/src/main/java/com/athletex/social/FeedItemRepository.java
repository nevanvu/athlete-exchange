package com.athletex.social;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedItemRepository extends JpaRepository<FeedItemEntity, UUID> {

    List<FeedItemEntity> findAllByOrderByOccurredAtDesc(Limit limit);

    List<FeedItemEntity> findBySlateIdOrderByOccurredAtDesc(UUID slateId, Limit limit);
}
