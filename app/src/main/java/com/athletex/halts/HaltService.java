package com.athletex.halts;

import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.MarketStatus;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import com.athletex.outbox.OutboxService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stale-quote protection from docs/fair-value-model.md. When one event moves a trading market's
 * fair value by more than the threshold (and by at least a minimum amount, so cheap athletes are
 * not halted by every basket), or rules an athlete OUT, the market halts in the same transaction
 * that reprices it, and the halt is published before the new fair value. New orders are rejected
 * until the minimum duration has passed; resting orders are left alone.
 */
@Service
public class HaltService {

    private static final Logger log = LoggerFactory.getLogger(HaltService.class);

    public record MarketHalted(String type, UUID athleteMarketId, UUID slateId, String reason,
                               String triggeredByEventId, Long fairValueBeforeCents, Long fairValueAfterCents,
                               Instant haltedAt, Instant resumeAfter) {
    }

    public record MarketResumed(String type, UUID athleteMarketId, UUID slateId, Instant resumedAt) {
    }

    private final AthleteMarketRepository marketRepository;
    private final SlateRepository slateRepository;
    private final MarketHaltRepository haltRepository;
    private final OutboxService outbox;
    private final String topic;
    private final double threshold;
    private final long minMoveCents;
    private final Duration minDuration;

    public HaltService(AthleteMarketRepository marketRepository,
                       SlateRepository slateRepository,
                       MarketHaltRepository haltRepository,
                       OutboxService outbox,
                       @Value("${athletex.kafka.market-events-topic:market-events}") String topic,
                       @Value("${athletex.halts.threshold:0.15}") double threshold,
                       @Value("${athletex.halts.min-move-cents:100}") long minMoveCents,
                       @Value("${athletex.halts.min-duration:PT30S}") Duration minDuration) {
        this.marketRepository = marketRepository;
        this.slateRepository = slateRepository;
        this.haltRepository = haltRepository;
        this.outbox = outbox;
        this.topic = topic;
        this.threshold = threshold;
        this.minMoveCents = minMoveCents;
        this.minDuration = minDuration;
    }

    /** Whether a single-event repricing is big enough to halt on. */
    public boolean isHaltWorthyMove(Long beforeCents, long afterCents) {
        if (beforeCents == null || beforeCents <= 0) {
            return false;
        }
        long move = Math.abs(afterCents - beforeCents);
        return move >= minMoveCents && move > threshold * beforeCents;
    }

    /**
     * Halts (or extends the halt of) a market. Must be called inside the transaction that holds
     * the market's row lock and is about to publish the new fair value. No-op unless the slate is trading.
     */
    @Transactional
    public boolean halt(AthleteMarketEntity market, String reason, String triggerEventId,
                        Long fairValueBeforeCents, Long fairValueAfterCents) {
        SlateStatus slateStatus = slateRepository.findById(market.getSlateId()).orElseThrow().getStatus();
        if (slateStatus != SlateStatus.OPEN_MARKET) {
            return false;
        }
        Instant now = Instant.now();
        Instant resumeAfter = now.plus(minDuration);
        if (market.getStatus() == MarketStatus.HALTED && market.getResumeAfter() == null) {
            resumeAfter = null; // a manual halt stays manual
        } else if (market.getResumeAfter() != null && market.getResumeAfter().isAfter(resumeAfter)) {
            resumeAfter = market.getResumeAfter();
        }
        market.halt(reason, now, resumeAfter);
        haltRepository.save(new MarketHaltEntity(market.getId(), reason, triggerEventId, fairValueBeforeCents,
                fairValueAfterCents, now, resumeAfter));
        outbox.enqueue(topic, market.getId().toString(), new MarketHalted("market_halted", market.getId(),
                market.getSlateId(), reason, triggerEventId, fairValueBeforeCents, fairValueAfterCents, now, resumeAfter));
        log.info("market {} halted until {}: {} (fair value {} -> {})", market.getId(), resumeAfter, reason,
                fairValueBeforeCents, fairValueAfterCents);
        return true;
    }

    /** Automatically halted markets whose minimum halt duration has passed. */
    public List<UUID> dueForResumption(Instant now) {
        return marketRepository.findByStatusAndResumeAfterLessThanEqual(MarketStatus.HALTED, now).stream()
                .map(AthleteMarketEntity::getId)
                .toList();
    }

    /** Lifts an automatic halt if it is still due; a halt extended in the meantime stays. */
    @Transactional
    public boolean resume(UUID marketId, Instant now) {
        AthleteMarketEntity market = marketRepository.findByIdForUpdate(marketId).orElseThrow();
        if (market.getStatus() != MarketStatus.HALTED || market.getResumeAfter() == null
                || market.getResumeAfter().isAfter(now)) {
            return false;
        }
        market.resume();
        haltRepository.findByMarketIdAndResumedAtIsNull(marketId).forEach(h -> h.markResumed(now));
        outbox.enqueue(topic, marketId.toString(), new MarketResumed("market_resumed", marketId, market.getSlateId(), now));
        log.info("market {} resumed", marketId);
        return true;
    }
}
