package com.athletex.settlement;

import com.athletex.TestcontainersConfiguration;
import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.Ulid;
import com.athletex.fairvalue.FairValueService;
import com.athletex.fairvalue.FairValueTickRepository;
import com.athletex.ingestion.EventIngestionService;
import com.athletex.ingestion.GameEventProcessor;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountRepository;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.market.AthleteEntity;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.AthleteRepository;
import com.athletex.market.GameEntity;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateLifecycleService;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateSetupService;
import com.athletex.market.SlateStatus;
import com.athletex.market.TeamEntity;
import com.athletex.market.TeamRepository;
import com.athletex.outbox.OutboxRepository;
import com.athletex.trading.IpoService;
import com.athletex.trading.OrderService;
import com.athletex.trading.PositionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** A slate with one game and a few athletes, driven by game events, for fair value and settlement tests. */
@SpringBootTest(properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false",
        "athletex.fair-value.refresh-ms=3600000",
        "athletex.ingestion.reorder-max-wait-ms=0",
        "athletex.ingestion.reorder-flush-ms=3600000"
})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractLiveSlateTest {

    protected static final long SEED_CENTS = 1_000_000L;

    @Autowired protected LedgerService ledgerService;
    @Autowired protected AccountRepository accountRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected TeamRepository teamRepository;
    @Autowired protected AthleteRepository athleteRepository;
    @Autowired protected AthleteMarketRepository marketRepository;
    @Autowired protected SlateSetupService setup;
    @Autowired protected SlateLifecycleService lifecycle;
    @Autowired protected SlateRepository slateRepository;
    @Autowired protected PositionRepository positionRepository;
    @Autowired protected OrderService orderService;
    @Autowired protected IpoService ipoService;
    @Autowired protected EventIngestionService ingestion;
    @Autowired protected GameEventProcessor processor;
    @Autowired protected SettlementService settlementService;
    @Autowired protected SettlementRunRepository runRepository;
    @Autowired protected SettlementPayoutRepository payoutRepository;
    @Autowired protected SettlementCorrectionRepository correctionRepository;
    @Autowired protected FairValueService fairValueService;
    @Autowired protected FairValueTickRepository tickRepository;
    @Autowired protected OutboxRepository outboxRepository;
    @Autowired protected JdbcTemplate jdbc;

    protected UUID newTrader() {
        String name = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UserEntity user = userRepository.save(new UserEntity(name, name + "@test.local", "x"));
        AccountEntity cash = accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_CASH));
        accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_ESCROW));
        ledgerService.post("test-seed:" + user.getId(), TransactionType.SEED_GRANT, List.of(
                new Posting(AccountEntity.HOUSE_ACCOUNT_ID, -SEED_CENTS),
                new Posting(cash.getId(), SEED_CENTS)));
        return user.getId();
    }

    protected long cash(UUID userId) {
        return accountRepository.findByOwnerUserIdAndType(userId, AccountType.USER_CASH).orElseThrow().getBalanceCents();
    }

    protected long poolBalance() {
        return accountRepository.findById(AccountEntity.SETTLEMENT_POOL_ACCOUNT_ID).orElseThrow().getBalanceCents();
    }

    protected long allBalances() {
        return accountRepository.findAll().stream().mapToLong(AccountEntity::getBalanceCents).sum();
    }

    protected Slate newSlate(double... projections) {
        return new Slate(projections);
    }

    /** One slate, one game, one athlete market per projection (index order). */
    protected final class Slate {
        public final UUID slateId;
        public final UUID gameId;
        public final List<UUID> athletes = new ArrayList<>();
        public final List<UUID> markets = new ArrayList<>();
        private long sequence = 0;

        Slate(double... projections) {
            String tag = UUID.randomUUID().toString().substring(0, 6);
            Instant opens = Instant.now().plus(Duration.ofHours(1));
            SlateEntity slate = setup.createSlate("live-" + tag, opens, opens.plusSeconds(1800));
            TeamEntity home = teamRepository.save(new TeamEntity("H" + tag, "Home"));
            TeamEntity away = teamRepository.save(new TeamEntity("A" + tag, "Away"));
            GameEntity game = setup.addGame(slate.getId(), home.getId(), away.getId(), opens.plusSeconds(3600));
            for (int i = 0; i < projections.length; i++) {
                AthleteEntity athlete = athleteRepository.save(
                        new AthleteEntity(i % 2 == 0 ? home.getId() : away.getId(), "Athlete " + tag + "-" + i, "SF"));
                athletes.add(athlete.getId());
                markets.add(setup.listMarket(slate.getId(), athlete.getId(), game.getId(),
                        BigDecimal.valueOf(projections[i]), BigDecimal.valueOf(32)).getId());
            }
            this.slateId = slate.getId();
            this.gameId = game.getId();
        }

        public void toIpo() {
            lifecycle.transition(slateId, SlateStatus.IPO, "test");
        }

        public void openMarket() {
            if (status() == SlateStatus.SCHEDULED) {
                toIpo();
            }
            lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "test");
        }

        public SlateStatus status() {
            return slateRepository.findById(slateId).orElseThrow().getStatus();
        }

        public AthleteMarketEntity market(int index) {
            return marketRepository.findById(markets.get(index)).orElseThrow();
        }

        public EventEnvelope next(EventPayload payload) {
            return EventEnvelope.create(Ulid.generate(), "test", gameId.toString(), slateId.toString(),
                    ++sequence, Instant.now(), payload);
        }

        /** Ingests the next event and returns it. */
        public EventEnvelope apply(EventPayload payload) {
            EventEnvelope event = next(payload);
            ingestion.ingest(event);
            return event;
        }

        /** Ingests a provider revision of {@code original} (new id and sequence, "corrects" set). */
        public EventEnvelope correct(EventEnvelope original, EventPayload revised) {
            EventEnvelope correction = original.correcting(original.eventId(), Ulid.generate(), ++sequence, revised);
            ingestion.ingest(correction);
            return correction;
        }

        public EventPayload.StatLine line(int athlete, int pts, int reb, int ast, boolean isFinal) {
            Map<String, Integer> stats = new LinkedHashMap<>();
            stats.put("pts", pts);
            stats.put("reb", reb);
            stats.put("ast", ast);
            stats.put("stl", 0);
            stats.put("blk", 0);
            stats.put("tov", 0);
            return new EventPayload.StatLine(athletes.get(athlete).toString(), stats, 30.0, isFinal);
        }

        public EventPayload.GameStatus clock(EventPayload.GameState state, int period, int clockSeconds) {
            return new EventPayload.GameStatus(state, period, clockSeconds);
        }

        public EventPayload.Injury injury(int athlete, EventPayload.InjuryStatus status) {
            return new EventPayload.Injury(athletes.get(athlete).toString(), status, "test", false);
        }

        public EventPayload.News news(int athlete, double impact, double confidence) {
            return new EventPayload.News(athletes.get(athlete).toString(), "test headline", impact, confidence);
        }
    }
}
