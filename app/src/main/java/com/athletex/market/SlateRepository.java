package com.athletex.market;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlateRepository extends JpaRepository<SlateEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SlateEntity s where s.id = :id")
    Optional<SlateEntity> findByIdForUpdate(@Param("id") UUID id);

    List<SlateEntity> findByStatusAndIpoOpensAtLessThanEqual(SlateStatus status, Instant now);

    List<SlateEntity> findByStatusAndIpoClosesAtLessThanEqual(SlateStatus status, Instant now);

    List<SlateEntity> findAllByOrderByCreatedAtDesc();
}
