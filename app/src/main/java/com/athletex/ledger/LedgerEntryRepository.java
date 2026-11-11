package com.athletex.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    List<LedgerEntryEntity> findByTransactionId(UUID transactionId);

    List<LedgerEntryEntity> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    @Query("select coalesce(sum(e.amountCents), 0) from LedgerEntryEntity e")
    long sumAllAmounts();

    @Query("select coalesce(sum(e.amountCents), 0) from LedgerEntryEntity e where e.accountId = :accountId")
    long sumByAccountId(@Param("accountId") UUID accountId);
}
