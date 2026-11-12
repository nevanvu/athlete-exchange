package com.athletex.fairvalue;

import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.halts.HaltService;
import com.athletex.ingestion.AthleteAvailabilityEntity;
import com.athletex.ingestion.AthleteAvailabilityRepository;
import com.athletex.ingestion.AthleteGameStatsEntity;
import com.athletex.ingestion.AthleteGameStatsRepository;
import com.athletex.ingestion.GameAthleteKey;
import com.athletex.ingestion.GameDataChanged;
import com.athletex.ingestion.NewsEventRepository;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.GameEntity;
import com.athletex.market.GameRepository;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateLifecycleHooks;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.outbox.OutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each athlete market's reference fair value current. Recomputes on every applied game
 * event (inside the ingestion transaction) and on a timer so news sentiment decays between
 * events. A change is persisted as a tick and published as fair_value_updated via the outbox.
 */
@Service
public class FairValueService implements SlateLifecycleHooks {

    public record FairValueUpdated(String type, UUID athleteMarketId, UUID slateId, UUID athleteId,
                                   long fairValueCents, Long previousFairValueCents, Components components,
                                   String asOfEventId, Instant computedAt) {
    }

    public record Components(double fpScored, double fpRemaining, double availability,
                             double newsFactor, double gameFractionRemaining) {
    }

    private static final int NEWS_LOOKBACK_TAUS = 8;

    /** Whether a repricing may halt the market, and on what grounds. */
    private enum HaltCheck {
        NONE, PRICE_MOVE, INJURY_OUT
    }

    private final AthleteMarketRepository marketRepository;
    private final SlateRepository slateRepository;
    private final GameRepository gameRepository;
    private final AthleteGameStatsRepository statsRepository;
    private final AthleteAvailabilityRepository availabilityRepository;
    private final NewsEventRepository newsRepository;
    private final FairValueTickRepository tickRepository;
    private final OutboxService outbox;
    private final HaltService haltService;
    private final String topic;

    public FairValueService(AthleteMarketRepository marketRepository,
                            SlateRepository slateRepository,
                            GameRepository gameRepository,
                            AthleteGameStatsRepository statsRepository,
                            AthleteAvailabilityRepository availabilityRepository,
                            NewsEventRepository newsRepository,
                            FairValueTickRepository tickRepository,
                            OutboxService outbox,
                            HaltService haltService,
                            @Value("${athletex.kafka.market-events-topic:market-events}") String topic) {
        this.marketRepository = marketRepository;
        this.slateRepository = slateRepository;
        this.gameRepository = gameRepository;
        this.statsRepository = statsRepository;
        this.availabilityRepository = availabilityRepository;
        this.newsRepository = newsRepository;
        this.tickRepository = tickRepository;
        this.outbox = outbox;
        this.haltService = haltService;
        this.topic = topic;
    }

    @EventListener
    public void onGameDataChanged(GameDataChanged change) {
        // Row locks (id order) serialize this repricing, and any halt it causes, with order placement.
        List<AthleteMarketEntity> affected = switch (change) {
            case GameDataChanged.GameClock clock -> marketRepository.findByGameIdForUpdate(clock.gameId());
            case GameDataChanged.AthleteStats s -> lockedMarketFor(s.gameId(), s.athleteId());
            case GameDataChanged.AthleteAvailability a -> lockedMarketFor(a.gameId(), a.athleteId());
            case GameDataChanged.AthleteNews n -> lockedMarketFor(n.gameId(), n.athleteId());
        };
        HaltCheck haltCheck = change instanceof GameDataChanged.AthleteAvailability a && ruledOut(a)
                ? HaltCheck.INJURY_OUT
                : HaltCheck.PRICE_MOVE;
        for (AthleteMarketEntity market : affected) {
            recompute(market, change.occurredAt(), change.eventId(), haltCheck);
        }
    }

    /** Pre-game fair values are set as the IPO opens; they equal the projection-based reserve. */
    @Override
    public void onTransition(UUID slateId, SlateStatus from, SlateStatus to, String trigger) {
        if (to == SlateStatus.IPO) {
            Instant now = Instant.now();
            marketRepository.findBySlateId(slateId).forEach(m -> recompute(m, now, null));
        }
    }

    /** Lets news sentiment decay between game events. */
    @Scheduled(fixedDelayString = "${athletex.fair-value.refresh-ms:60000}")
    @Transactional
    public void refreshOpenMarkets() {
        Instant now = Instant.now();
        for (SlateEntity slate : slateRepository.findByStatus(SlateStatus.OPEN_MARKET)) {
            marketRepository.findBySlateId(slate.getId()).forEach(m -> recompute(m, now, null));
        }
    }

    /** Fair value of a settled market is its settlement value, by definition. */
    public void recordSettlementValue(AthleteMarketEntity market, long valueCents, BigDecimal fantasyPoints,
                                      String asOfEventId) {
        FairValueModel.Result result = new FairValueModel.Result(valueCents, fantasyPoints.doubleValue(), 0.0,
                1.0, 1.0, 0.0);
        persist(market, result, asOfEventId, Instant.now());
    }

    /** Reprices without halting (timer refresh, IPO open, explicit recomputation). */
    @Transactional
    public FairValueModel.Result recompute(AthleteMarketEntity market, Instant at, String asOfEventId) {
        return recompute(market, at, asOfEventId, HaltCheck.NONE);
    }

    private FairValueModel.Result recompute(AthleteMarketEntity market, Instant at, String asOfEventId,
                                            HaltCheck haltCheck) {
        SlateStatus slateStatus = slateRepository.findById(market.getSlateId()).orElseThrow().getStatus();
        if (slateStatus.isTerminal()) {
            return null; // settled markets are pinned to their settlement value
        }
        FairValueModel.Result result = FairValueModel.compute(inputs(market, at));
        Long previous = market.getFairValueCents();
        boolean changed = previous == null || previous != result.fairValueCents();

        // Halt first, so the halt is committed and published ahead of the price that caused it.
        if (haltCheck == HaltCheck.INJURY_OUT) {
            haltService.halt(market, "injury: athlete ruled OUT", asOfEventId, previous, result.fairValueCents());
        } else if (haltCheck == HaltCheck.PRICE_MOVE && changed
                && haltService.isHaltWorthyMove(previous, result.fairValueCents())) {
            long percent = Math.round(100.0 * Math.abs(result.fairValueCents() - previous) / previous);
            haltService.halt(market, "fair value moved " + percent + "% on one event", asOfEventId, previous,
                    result.fairValueCents());
        }
        if (changed) {
            persist(market, result, asOfEventId, at);
        }
        return result;
    }

    public FairValueModel.Inputs inputs(AthleteMarketEntity market, Instant at) {
        GameEntity game = gameRepository.findById(market.getGameId()).orElseThrow();
        GameAthleteKey key = new GameAthleteKey(market.getGameId(), market.getAthleteId());
        double scored = statsRepository.findById(key)
                .map(AthleteGameStatsEntity::getFantasyPoints).map(BigDecimal::doubleValue).orElse(0.0);
        InjuryStatus availability = availabilityRepository.findById(key)
                .map(AthleteAvailabilityEntity::getStatus).orElse(InjuryStatus.ACTIVE);
        List<FairValueModel.News> news = newsRepository
                .findByAthleteIdAndOccurredAtAfterOrderByOccurredAtDesc(market.getAthleteId(),
                        at.minus(FairValueModel.NEWS_TAU.multipliedBy(NEWS_LOOKBACK_TAUS)))
                .stream()
                .map(n -> new FairValueModel.News(n.getImpact().doubleValue(), n.getConfidence().doubleValue(),
                        n.getOccurredAt()))
                .toList();
        return new FairValueModel.Inputs(market.getProjectedFantasyPoints().doubleValue(), scored,
                game.getStatus(), game.getPeriod(), game.getClockSecondsRemaining(), availability, news, at);
    }

    private void persist(AthleteMarketEntity market, FairValueModel.Result result, String asOfEventId, Instant at) {
        Long previous = market.getFairValueCents();
        market.updateFairValue(result.fairValueCents(), asOfEventId, at);
        tickRepository.save(new FairValueTickEntity(market.getId(), result, asOfEventId, at));
        outbox.enqueue(topic, market.getId().toString(), new FairValueUpdated("fair_value_updated",
                market.getId(), market.getSlateId(), market.getAthleteId(), result.fairValueCents(), previous,
                new Components(result.fantasyPointsScored(), result.fantasyPointsRemaining(), result.availability(),
                        result.newsFactor(), result.gameFractionRemaining()),
                asOfEventId, at));
    }

    private List<AthleteMarketEntity> lockedMarketFor(UUID gameId, UUID athleteId) {
        return marketRepository.findByGameIdAndAthleteIdForUpdate(gameId, athleteId).stream().toList();
    }

    private boolean ruledOut(GameDataChanged.AthleteAvailability change) {
        return availabilityRepository.findById(new GameAthleteKey(change.gameId(), change.athleteId()))
                .map(a -> a.getStatus() == InjuryStatus.OUT)
                .orElse(false);
    }
}
