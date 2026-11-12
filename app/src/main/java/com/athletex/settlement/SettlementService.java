package com.athletex.settlement;

import com.athletex.fairvalue.FairValueService;
import com.athletex.ingestion.AthleteGameStatsEntity;
import com.athletex.ingestion.AthleteGameStatsRepository;
import com.athletex.ingestion.GameAthleteKey;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.LedgerTransactionEntity;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.Pricing;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateLifecycleService;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.trading.OrderRepository;
import com.athletex.trading.OrderStatus;
import com.athletex.trading.PositionEntity;
import com.athletex.trading.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-of-slate payout per docs/slate-lifecycle.md. One transaction per slate: every holder is
 * paid quantity x clamp(fantasy_points x $0.25, $1, $100) from the settlement pool in a single
 * ledger posting, shares are retired, and the slate moves CLOSED -> SETTLED. Running it again,
 * concurrently or after a crash, finds the slate already settled and does nothing.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final List<OrderStatus> RESTING = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    public enum Outcome {
        SETTLED, ALREADY_SETTLED, NOT_CLOSED, NOT_READY
    }

    public record Result(Outcome outcome, String detail) {
    }

    private final SlateRepository slateRepository;
    private final AthleteMarketRepository marketRepository;
    private final AthleteGameStatsRepository statsRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final SettlementRunRepository runRepository;
    private final SettlementPayoutRepository payoutRepository;
    private final LedgerService ledgerService;
    private final SlateLifecycleService lifecycle;
    private final FairValueService fairValueService;

    public SettlementService(SlateRepository slateRepository,
                             AthleteMarketRepository marketRepository,
                             AthleteGameStatsRepository statsRepository,
                             PositionRepository positionRepository,
                             OrderRepository orderRepository,
                             SettlementRunRepository runRepository,
                             SettlementPayoutRepository payoutRepository,
                             LedgerService ledgerService,
                             SlateLifecycleService lifecycle,
                             FairValueService fairValueService) {
        this.slateRepository = slateRepository;
        this.marketRepository = marketRepository;
        this.statsRepository = statsRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.runRepository = runRepository;
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.lifecycle = lifecycle;
        this.fairValueService = fairValueService;
    }

    @Transactional
    public Result settle(UUID slateId) {
        SlateEntity slate = slateRepository.findByIdForUpdate(slateId)
                .orElseThrow(() -> new IllegalArgumentException("unknown slate " + slateId));
        if (slate.getStatus() == SlateStatus.SETTLED || runRepository.existsBySlateId(slateId)) {
            return new Result(Outcome.ALREADY_SETTLED, null);
        }
        if (slate.getStatus() != SlateStatus.CLOSED) {
            return new Result(Outcome.NOT_CLOSED, "slate is " + slate.getStatus());
        }

        List<AthleteMarketEntity> markets = marketRepository.findBySlateId(slateId);
        Optional<String> notReady = readinessProblem(markets);
        if (notReady.isPresent()) {
            return new Result(Outcome.NOT_READY, notReady.get());
        }

        UUID runId = UUID.randomUUID();
        Map<UUID, Long> creditsByCashAccount = new LinkedHashMap<>();
        List<SettlementPayoutEntity> payouts = new ArrayList<>();
        long totalPaid = 0;

        for (AthleteMarketEntity market : markets) {
            BigDecimal fantasyPoints = finalFantasyPoints(market);
            long value = Pricing.settlementValueCents(fantasyPoints);
            market.setSettlement(value, fantasyPoints);

            for (PositionEntity position : positionRepository.findByMarketId(market.getId())) {
                if (position.getQuantity() == 0) {
                    continue;
                }
                if (!position.getUserId().equals(PositionEntity.HOUSE_USER_ID)) {
                    SettlementPayoutEntity payout = new SettlementPayoutEntity(runId, market.getId(),
                            position.getUserId(), position.getQuantity(), value);
                    payouts.add(payout);
                    creditsByCashAccount.merge(ledgerService.accountIdOf(position.getUserId(), AccountType.USER_CASH),
                            payout.getAmountCents(), Long::sum);
                    totalPaid += payout.getAmountCents();
                }
                // House inventory is retired too; paying the house from the house's own pool would be a no-op.
                position.retireAtSettlement(value);
            }
            fairValueService.recordSettlementValue(market, value, fantasyPoints, null);
        }

        SettlementRunEntity run = runRepository.save(new SettlementRunEntity(runId, slateId, Instant.now(), totalPaid));
        if (totalPaid > 0) {
            List<Posting> postings = new ArrayList<>();
            postings.add(new Posting(AccountEntity.SETTLEMENT_POOL_ACCOUNT_ID, -totalPaid));
            creditsByCashAccount.forEach((account, amount) -> postings.add(new Posting(account, amount)));
            LedgerTransactionEntity tx = ledgerService.post("settlement:" + slateId, TransactionType.SLATE_SETTLEMENT, postings);
            run.setLedgerTransactionId(tx.getId());
        }
        payoutRepository.saveAll(payouts);
        lifecycle.transition(slateId, SlateStatus.SETTLED, "settlement:" + runId);
        log.info("settled slate {}: {} market(s), {} payout(s), {} cents paid", slateId, markets.size(),
                payouts.size(), totalPaid);
        return new Result(Outcome.SETTLED, null);
    }

    /**
     * Settlement needs the official box score: any athlete with a provisional stat line holds
     * it up. An athlete with no line at all did not play and settles at zero points (the floor).
     */
    private Optional<String> readinessProblem(List<AthleteMarketEntity> markets) {
        for (AthleteMarketEntity market : markets) {
            Optional<AthleteGameStatsEntity> stats = statsRepository.findById(key(market));
            if (stats.isPresent() && !stats.get().isFinal()) {
                return Optional.of("athlete " + market.getAthleteId() + " has no final stat line yet");
            }
            if (!orderRepository.findByMarketIdAndStatusInOrderByCreatedAtAsc(market.getId(), RESTING).isEmpty()) {
                return Optional.of("market " + market.getId() + " still has resting orders");
            }
        }
        return Optional.empty();
    }

    private BigDecimal finalFantasyPoints(AthleteMarketEntity market) {
        return statsRepository.findById(key(market))
                .map(AthleteGameStatsEntity::getFantasyPoints)
                .orElse(BigDecimal.ZERO.setScale(2));
    }

    private static GameAthleteKey key(AthleteMarketEntity market) {
        return new GameAthleteKey(market.getGameId(), market.getAthleteId());
    }
}
