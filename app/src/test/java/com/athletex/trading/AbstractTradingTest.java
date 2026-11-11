package com.athletex.trading;

import com.athletex.TestcontainersConfiguration;
import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
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
import com.athletex.market.SlateSetupService;
import com.athletex.market.SlateStatus;
import com.athletex.market.TeamEntity;
import com.athletex.market.TeamRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false"
})
@Import(TestcontainersConfiguration.class)
abstract class AbstractTradingTest {

    /** Low projection so the IPO reserve price (8 x 25 = 200 cents) sits below test bids. */
    static final BigDecimal LOW_PROJECTION = BigDecimal.valueOf(8);
    static final long SEED_CENTS = 1_000_000L;

    @Autowired protected LedgerService ledgerService;
    @Autowired protected AccountRepository accountRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected TeamRepository teamRepository;
    @Autowired protected AthleteRepository athleteRepository;
    @Autowired protected AthleteMarketRepository marketRepository;
    @Autowired protected SlateSetupService setup;
    @Autowired protected SlateLifecycleService lifecycle;
    @Autowired protected PositionRepository positionRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected IpoBidRepository bidRepository;
    @Autowired protected IpoService ipoService;
    @Autowired protected OrderService orderService;
    @Autowired protected MatchingEngineRegistry registry;

    protected record MarketFixture(SlateEntity slate, GameEntity game, AthleteMarketEntity market) {
    }

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

    protected MarketFixture scheduledMarket(BigDecimal projectedFantasyPoints) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Instant opens = Instant.now().plus(Duration.ofHours(1));
        SlateEntity slate = setup.createSlate("slate-" + tag, opens, opens.plus(Duration.ofMinutes(30)));
        TeamEntity home = teamRepository.save(new TeamEntity("H" + tag.substring(0, 5), "Home"));
        TeamEntity away = teamRepository.save(new TeamEntity("A" + tag.substring(0, 5), "Away"));
        GameEntity game = setup.addGame(slate.getId(), home.getId(), away.getId(), opens.plusSeconds(3600));
        AthleteEntity athlete = athleteRepository.save(new AthleteEntity(home.getId(), "Player " + tag, "PG"));
        AthleteMarketEntity market = setup.listMarket(slate.getId(), athlete.getId(), game.getId(),
                projectedFantasyPoints, BigDecimal.valueOf(30));
        return new MarketFixture(slate, game, market);
    }

    protected void toIpo(MarketFixture f) {
        lifecycle.transition(f.slate().getId(), SlateStatus.IPO, "test");
    }

    protected void openMarket(MarketFixture f) {
        lifecycle.transition(f.slate().getId(), SlateStatus.OPEN_MARKET, "test");
    }

    protected long cash(UUID userId) {
        return balance(userId, AccountType.USER_CASH);
    }

    protected long escrow(UUID userId) {
        return balance(userId, AccountType.USER_ESCROW);
    }

    protected long houseCash() {
        return accountRepository.findById(AccountEntity.HOUSE_ACCOUNT_ID).orElseThrow().getBalanceCents();
    }

    protected PositionEntity position(UUID userId, UUID marketId) {
        return positionRepository.findByUserIdAndMarketId(userId, marketId).orElseThrow();
    }

    protected int shares(UUID userId, UUID marketId) {
        return positionRepository.findByUserIdAndMarketId(userId, marketId).map(PositionEntity::getQuantity).orElse(0);
    }

    protected AthleteMarketEntity reload(AthleteMarketEntity market) {
        return marketRepository.findById(market.getId()).orElseThrow();
    }

    private long balance(UUID userId, AccountType type) {
        return accountRepository.findByOwnerUserIdAndType(userId, type).orElseThrow().getBalanceCents();
    }
}
