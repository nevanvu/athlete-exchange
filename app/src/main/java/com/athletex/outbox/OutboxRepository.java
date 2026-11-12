package com.athletex.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    List<OutboxEntity> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    long countByPublishedAtIsNull();

    List<OutboxEntity> findByMessageKeyOrderByCreatedAtAsc(String messageKey);
}
