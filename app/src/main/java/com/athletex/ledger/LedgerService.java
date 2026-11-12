package com.athletex.ledger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry ledger. Every transaction is a set of postings that sum to zero;
 * balances are materialized on accounts and updated under row locks taken in
 * sorted account-id order, so concurrent postings cannot deadlock.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;

    public LedgerService(LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository,
                         AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public LedgerTransactionEntity post(String idempotencyKey, TransactionType type, List<Posting> postings) {
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two postings");
        }
        if (postings.stream().anyMatch(p -> p.amountCents() == 0)) {
            throw new IllegalArgumentException("zero-amount postings are not allowed");
        }
        long sum = postings.stream().mapToLong(Posting::amountCents).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("postings must sum to zero, got " + sum);
        }

        Optional<LedgerTransactionEntity> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Map<UUID, Long> deltaByAccount = postings.stream()
                .collect(Collectors.groupingBy(Posting::accountId, Collectors.summingLong(Posting::amountCents)));
        List<UUID> lockOrder = deltaByAccount.keySet().stream().sorted().toList();
        for (UUID accountId : lockOrder) {
            AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown account " + accountId));
            long delta = deltaByAccount.get(accountId);
            long newBalance = account.getBalanceCents() + delta;
            if (newBalance < 0 && account.getType().mustStayNonNegative() && !type.permitsOverdraft()) {
                throw new InsufficientFundsException(accountId, delta, account.getBalanceCents());
            }
            account.setBalanceCents(newBalance);
        }

        LedgerTransactionEntity transaction =
                transactionRepository.save(new LedgerTransactionEntity(idempotencyKey, type));
        for (Posting posting : postings) {
            entryRepository.save(new LedgerEntryEntity(transaction.getId(), posting.accountId(), posting.amountCents()));
        }
        return transaction;
    }

    /** Moves funds from a user's cash account into their escrow account. */
    @Transactional
    public LedgerTransactionEntity lockFunds(String idempotencyKey, UUID userId, long amountCents) {
        return cashEscrowTransfer(idempotencyKey, TransactionType.ESCROW_LOCK, userId, amountCents);
    }

    /** Releases escrowed funds back to the user's cash account. */
    @Transactional
    public LedgerTransactionEntity releaseFunds(String idempotencyKey, UUID userId, long amountCents) {
        return cashEscrowTransfer(idempotencyKey, TransactionType.ESCROW_RELEASE, userId, -amountCents);
    }

    private LedgerTransactionEntity cashEscrowTransfer(String idempotencyKey, TransactionType type,
                                                       UUID userId, long amountCents) {
        if (amountCents == 0) {
            throw new IllegalArgumentException("amount must be non-zero");
        }
        UUID cash = accountIdOf(userId, AccountType.USER_CASH);
        UUID escrow = accountIdOf(userId, AccountType.USER_ESCROW);
        return post(idempotencyKey, type, List.of(
                new Posting(cash, -amountCents),
                new Posting(escrow, amountCents)));
    }

    public UUID accountIdOf(UUID userId, AccountType type) {
        return accountRepository.findByOwnerUserIdAndType(userId, type)
                .map(AccountEntity::getId)
                .orElseThrow(() -> new IllegalArgumentException("user %s has no %s account".formatted(userId, type)));
    }
}
