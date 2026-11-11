package com.athletex.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.TestcontainersConfiguration;
import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LedgerServiceTest {

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private LedgerEntryRepository entryRepository;
    @Autowired
    private UserRepository userRepository;

    private record TestUser(UUID userId, UUID cashAccountId, UUID escrowAccountId) {
    }

    private TestUser newUserWithCash(long cents) {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UserEntity user = userRepository.save(new UserEntity(name, name + "@test.local", "x"));
        AccountEntity cash = accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_CASH));
        AccountEntity escrow = accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_ESCROW));
        if (cents > 0) {
            ledgerService.post("test-seed:" + user.getId(), TransactionType.SEED_GRANT, List.of(
                    new Posting(AccountEntity.HOUSE_ACCOUNT_ID, -cents),
                    new Posting(cash.getId(), cents)));
        }
        return new TestUser(user.getId(), cash.getId(), escrow.getId());
    }

    private long balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalanceCents();
    }

    @Test
    void rejectsPostingsThatDoNotSumToZero() {
        TestUser user = newUserWithCash(10_00);
        assertThatThrownBy(() -> ledgerService.post("bad:" + UUID.randomUUID(), TransactionType.TRANSFER, List.of(
                new Posting(user.cashAccountId(), -5_00),
                new Posting(user.escrowAccountId(), 4_00))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to zero");
    }

    @Test
    void duplicateIdempotencyKeyAppliesOnlyOnce() {
        TestUser user = newUserWithCash(100_00);
        String key = "dup:" + UUID.randomUUID();
        List<Posting> postings = List.of(
                new Posting(user.cashAccountId(), -30_00),
                new Posting(user.escrowAccountId(), 30_00));

        LedgerTransactionEntity first = ledgerService.post(key, TransactionType.ESCROW_LOCK, postings);
        LedgerTransactionEntity second = ledgerService.post(key, TransactionType.ESCROW_LOCK, postings);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(balance(user.cashAccountId())).isEqualTo(70_00);
        assertThat(balance(user.escrowAccountId())).isEqualTo(30_00);
    }

    @Test
    void rejectsOverdraftOnUserAccounts() {
        TestUser rich = newUserWithCash(50_00);
        TestUser poor = newUserWithCash(10_00);

        assertThatThrownBy(() -> ledgerService.post("od:" + UUID.randomUUID(), TransactionType.TRANSFER, List.of(
                new Posting(poor.cashAccountId(), -20_00),
                new Posting(rich.cashAccountId(), 20_00))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(poor.cashAccountId())).isEqualTo(10_00);
        assertThat(balance(rich.cashAccountId())).isEqualTo(50_00);
    }

    @Test
    void escrowLockAndReleaseRoundTrip() {
        TestUser user = newUserWithCash(100_00);

        ledgerService.lockFunds("lock:" + user.userId(), user.userId(), 40_00);
        assertThat(balance(user.cashAccountId())).isEqualTo(60_00);
        assertThat(balance(user.escrowAccountId())).isEqualTo(40_00);

        ledgerService.releaseFunds("release:" + user.userId(), user.userId(), 40_00);
        assertThat(balance(user.cashAccountId())).isEqualTo(100_00);
        assertThat(balance(user.escrowAccountId())).isEqualTo(0);
    }

    @Test
    void randomTransferSequenceConservesMoneyAndReconciles() {
        List<TestUser> users = List.of(newUserWithCash(100_00), newUserWithCash(100_00), newUserWithCash(100_00));
        Random random = new Random(42);

        for (int i = 0; i < 200; i++) {
            TestUser from = users.get(random.nextInt(users.size()));
            TestUser to = users.get(random.nextInt(users.size()));
            if (from == to) {
                continue;
            }
            long amount = 1 + random.nextInt(40_00);
            try {
                ledgerService.post("rand:" + i + ":" + UUID.randomUUID(), TransactionType.TRANSFER, List.of(
                        new Posting(from.cashAccountId(), -amount),
                        new Posting(to.cashAccountId(), amount)));
            } catch (InsufficientFundsException expected) {
                // overdraft attempts are expected and must leave balances untouched
            }
        }

        // Global conservation: every posting sums to zero, so all balances sum to zero.
        long totalBalances = accountRepository.findAll().stream()
                .mapToLong(AccountEntity::getBalanceCents).sum();
        assertThat(totalBalances).isZero();
        assertThat(entryRepository.sumAllAmounts()).isZero();

        // Reconciliation: materialized balances match the entry history.
        for (TestUser user : users) {
            assertThat(balance(user.cashAccountId())).isEqualTo(entryRepository.sumByAccountId(user.cashAccountId()));
            assertThat(balance(user.cashAccountId())).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void concurrentTransfersNeverLoseMoneyOrOverdraw() throws Exception {
        TestUser alice = newUserWithCash(100_00);
        TestUser bob = newUserWithCash(100_00);
        int threads = 8;
        int opsPerThread = 25;
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int seed = t;
            pool.submit(() -> {
                Random random = new Random(seed);
                start.await();
                for (int i = 0; i < opsPerThread; i++) {
                    TestUser from = random.nextBoolean() ? alice : bob;
                    TestUser to = from == alice ? bob : alice;
                    long amount = 1 + random.nextInt(30_00);
                    try {
                        ledgerService.post("conc:" + seed + ":" + i, TransactionType.TRANSFER, List.of(
                                new Posting(from.cashAccountId(), -amount),
                                new Posting(to.cashAccountId(), amount)));
                    } catch (InsufficientFundsException e) {
                        rejected.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

        long aliceCash = balance(alice.cashAccountId());
        long bobCash = balance(bob.cashAccountId());
        assertThat(aliceCash).isGreaterThanOrEqualTo(0);
        assertThat(bobCash).isGreaterThanOrEqualTo(0);
        assertThat(aliceCash + bobCash).isEqualTo(200_00);
        assertThat(aliceCash).isEqualTo(entryRepository.sumByAccountId(alice.cashAccountId()));
        assertThat(bobCash).isEqualTo(entryRepository.sumByAccountId(bob.cashAccountId()));
    }
}
