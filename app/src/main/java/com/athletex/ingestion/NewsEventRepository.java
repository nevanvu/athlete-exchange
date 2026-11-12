package com.athletex.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsEventRepository extends JpaRepository<NewsEventEntity, String> {

    List<NewsEventEntity> findByAthleteIdAndOccurredAtAfterOrderByOccurredAtDesc(UUID athleteId, Instant after);
}
