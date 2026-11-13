package com.athletex.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    List<OutboxEntity> findTop100ByPublishedAtIsNullOrderBySeqAsc();

    long countByPublishedAtIsNull();

    List<OutboxEntity> findByMessageKeyOrderBySeqAsc(String messageKey);

    @Modifying
    @Query("delete from OutboxEntity o where o.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
