package com.athletex.settlement;

import com.athletex.fairvalue.FairValueService;
import com.athletex.ingestion.AthleteGameStatsRepository;
import com.athletex.ingestion.GameAthleteKey;
import com.athletex.ingestion.GameDataChanged;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.Pricing;
import com.athletex.trading.PositionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Handles stat revisions that land after a slate settled. Within the correction window the
 * value difference is paid (or clawed back) against the holdings snapshotted at settlement, as
 * a new ledger transaction keyed by the correcting event; the original settlement is never edited.
 * Runs inside the ingestion transaction, so the revision and its money move together.
 */
@Service
public class SettlementCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementCorrectionService.class);

    private final AthleteMarketRepository marketRepository;
    private final AthleteGameStatsRepository statsRepository;
    private final SettlementRunRepository runRepository;
    private final SettlementPayoutRepository payoutRepository;
    private final SettlementCorrectionRepository correctionRepository;
    private final PositionRepository positionRepository;
    private final LedgerService ledgerService;
    private final FairValueService fairValueService;
    private final Duration correctionWindow;

    public SettlementCorrectionService(AthleteMarketRepository marketRepository,
                                       AthleteGameStatsRepository statsRepository,
                                       SettlementRunRepository runRepository,
                                       SettlementPayoutRepository payoutRepository,
                                       SettlementCorrectionRepository correctionRepository,
                                       PositionRepository positionRepository,
                                       LedgerService ledgerService,
                                       FairValueService fairValueService,
                                       @Value("${athletex.settlement.correction-window:PT48H}") Duration correctionWindow) {
        this.marketRepository = marketRepository;
        this.statsRepository = statsRepository;
        this.runRepository = runRepository;
        this.payoutRepository = payoutRepository;
        this.correctionRepository = correctionRepository;
        this.positionRepository = positionRepository;
        this.ledgerService = ledgerService;
        this.fairValueService = fairValueService;
        this.correctionWindow = correctionWindow;
    }

    @EventListener
    public void onGameDataChanged(GameDataChanged change) {
        if (!(change instanceof GameDataChanged.AthleteStats stats) || !stats.isFinal()) {
            return;
        }
        AthleteMarketEntity market = marketRepository.findByGameIdAndAthleteId(stats.gameId(), stats.athleteId())
                .orElse(null);
        if (market == null || market.getSettlementValueCents() == null) {
            return; // not settled yet: settlement will read the latest line itself
        }
        SettlementRunEntity run = runRepository.findBySlateId(market.getSlateId()).orElse(null);
        if (run == null || correctionRepository.existsByEventId(stats.eventId())) {
            return;
        }

        BigDecimal newPoints = statsRepository.findById(new GameAthleteKey(stats.gameId(), stats.athleteId()))
                .orElseThrow().getFantasyPoints();
        long oldValue = market.getSettlementValueCents();
        long newValue = Pricing.settlementValueCents(newPoints);
        if (newValue == oldValue) {
            market.setSettlement(oldValue, newPoints);
            return;
        }

        if (Instant.now().isAfter(run.getSettledAt().plus(correctionWindow))) {
            correctionRepository.save(new SettlementCorrectionEntity(market.getId(), stats.eventId(), oldValue,
                    newValue, newPoints, 0, false, "outside correction window"));
            log.warn("stat correction {} for market {} arrived after the correction window; not applied",
                    stats.eventId(), market.getId());
            return;
        }

        long delta = newValue - oldValue;
        List<SettlementPayoutEntity> payouts = payoutRepository.findByMarketId(market.getId());
        Map<java.util.UUID, Long> byCashAccount = new LinkedHashMap<>();
        long total = 0;
        for (SettlementPayoutEntity payout : payouts) {
            long adjustment = payout.getQuantity() * delta;
            byCashAccount.merge(ledgerService.accountIdOf(payout.getUserId(), AccountType.USER_CASH), adjustment, Long::sum);
            positionRepository.findByUserIdAndMarketId(payout.getUserId(), market.getId())
                    .ifPresent(p -> p.adjustRealized(adjustment));
            total += adjustment;
        }
        if (total != 0) {
            List<Posting> postings = new ArrayList<>();
            postings.add(new Posting(AccountEntity.SETTLEMENT_POOL_ACCOUNT_ID, -total));
            byCashAccount.forEach((account, amount) -> {
                if (amount != 0) {
                    postings.add(new Posting(account, amount));
                }
            });
            ledgerService.post("correction:" + stats.eventId(), TransactionType.SETTLEMENT_CORRECTION, postings);
        }

        market.setSettlement(newValue, newPoints);
        fairValueService.recordSettlementValue(market, newValue, newPoints, stats.eventId());
        correctionRepository.save(new SettlementCorrectionEntity(market.getId(), stats.eventId(), oldValue, newValue,
                newPoints, total, true, null));
        log.info("applied stat correction {} to market {}: {} -> {} cents/share, {} cents across {} holder(s)",
                stats.eventId(), market.getId(), oldValue, newValue, total, payouts.size());
    }
}
