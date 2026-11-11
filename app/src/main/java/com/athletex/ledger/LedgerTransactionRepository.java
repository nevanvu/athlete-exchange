package com.athletex.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, UUID> {

    Optional<LedgerTransactionEntity> findByIdempotencyKey(String idempotencyKey);
}
