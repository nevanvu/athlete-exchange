package com.athletex.market;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    Optional<TeamEntity> findByCode(String code);
}
