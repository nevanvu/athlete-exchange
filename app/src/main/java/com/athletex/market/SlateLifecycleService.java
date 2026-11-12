package com.athletex.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Slate state machine per docs/slate-lifecycle.md. Transitions lock the slate row,
 * are idempotent under redelivery (moving to the current or an earlier state is a
 * no-op), and every applied transition is recorded in the audit table.
 */
@Service
public class SlateLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SlateLifecycleService.class);

    private final SlateRepository slateRepository;
    private final GameRepository gameRepository;
    private final AthleteMarketRepository marketRepository;
    private final SlateTransitionRepository transitionRepository;
    private final List<SlateLifecycleHooks> hooks;

    public SlateLifecycleService(SlateRepository slateRepository,
                                 GameRepository gameRepository,
                                 AthleteMarketRepository marketRepository,
                                 SlateTransitionRepository transitionRepository,
                                 List<SlateLifecycleHooks> hooks) {
        this.slateRepository = slateRepository;
        this.gameRepository = gameRepository;
        this.marketRepository = marketRepository;
        this.transitionRepository = transitionRepository;
        this.hooks = hooks;
    }

    /**
     * Moves the slate to {@code to} if legal. Returns true when the transition was applied,
     * false when it was an idempotent no-op (already at or past {@code to}).
     */
    @Transactional
    public boolean transition(UUID slateId, SlateStatus to, String trigger) {
        SlateEntity slate = slateRepository.findByIdForUpdate(slateId)
                .orElseThrow(() -> new IllegalArgumentException("unknown slate " + slateId));
        SlateStatus from = slate.getStatus();

        if (from == to) {
            return false;
        }
        if (from.isTerminal()) {
            throw new IllegalStateException("slate %s is %s (terminal); cannot move to %s".formatted(slateId, from, to));
        }
        if (to != SlateStatus.VOIDED) {
            if (to.order() <= from.order()) {
                return false; // redelivered trigger for a state we already passed
            }
            if (to.order() != from.order() + 1) {
                throw new IllegalStateException(
                        "slate %s cannot skip from %s to %s".formatted(slateId, from, to));
            }
            checkGuard(slate, to);
        }

        switch (to) {
            case OPEN_MARKET -> hooks.forEach(h -> h.onIpoClosing(slateId));
            case CLOSED -> afterCommit(() -> hooks.forEach(h -> h.onSlateClosed(slateId)));
            case VOIDED -> {
                hooks.forEach(h -> h.onSlateVoiding(slateId));
                afterCommit(() -> hooks.forEach(h -> h.onSlateVoided(slateId)));
            }
            default -> {
            }
        }

        slate.setStatus(to);
        transitionRepository.save(new SlateTransitionEntity(slateId, from, to, trigger));
        hooks.forEach(h -> h.onTransition(slateId, from, to, trigger));
        log.info("slate {} transitioned {} -> {} ({})", slateId, from, to, trigger);
        return true;
    }

    /**
     * Order cleanup must run after the status change is visible: orders are placed under an
     * in-memory market lock, so once CLOSED is committed no new order can slip in, and every
     * order that committed before is still in the DB to be cancelled.
     */
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void checkGuard(SlateEntity slate, SlateStatus to) {
        switch (to) {
            case IPO -> {
                if (marketRepository.countBySlateId(slate.getId()) == 0) {
                    throw new IllegalStateException("slate %s has no athlete markets; cannot open IPO".formatted(slate.getId()));
                }
            }
            case CLOSED -> {
                if (gameRepository.countBySlateId(slate.getId()) == 0) {
                    throw new IllegalStateException("slate %s has no games; cannot close".formatted(slate.getId()));
                }
                long unfinished = gameRepository.countBySlateIdAndStatusNot(slate.getId(), GameStatus.FINAL);
                if (unfinished > 0) {
                    throw new IllegalStateException(
                            "slate %s still has %d unfinished game(s)".formatted(slate.getId(), unfinished));
                }
            }
            // OPEN_MARKET: IPO clearing becomes a guard in M4. SETTLED: settlement job arrives in M6.
            case OPEN_MARKET, SETTLED -> {
            }
            default -> throw new IllegalStateException("no guard defined for " + to);
        }
    }

    /** Scheduler entry point: applies all time-based transitions that are due. */
    @Transactional
    public void advanceDueSlates(Instant now) {
        for (SlateEntity slate : slateRepository.findByStatusAndIpoOpensAtLessThanEqual(SlateStatus.SCHEDULED, now)) {
            try {
                transition(slate.getId(), SlateStatus.IPO, "scheduler:ipo_opens_at");
            } catch (IllegalStateException e) {
                log.warn("slate {} not advanced to IPO: {}", slate.getId(), e.getMessage());
            }
        }
        for (SlateEntity slate : slateRepository.findByStatusAndIpoClosesAtLessThanEqual(SlateStatus.IPO, now)) {
            transition(slate.getId(), SlateStatus.OPEN_MARKET, "scheduler:ipo_closes_at");
        }
    }

    /**
     * Records a game status change and closes the slate when its last game goes FINAL.
     * Called by event ingestion inside its transaction, so it must not throw for an
     * expected situation: a slate that is not trading yet simply stays where it is.
     */
    @Transactional
    public void recordGameStatus(UUID gameId, GameStatus status, String trigger) {
        GameEntity game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("unknown game " + gameId));
        game.setStatus(status);
        if (status != GameStatus.FINAL
                || gameRepository.countBySlateIdAndStatusNot(game.getSlateId(), GameStatus.FINAL) > 0) {
            return;
        }
        SlateStatus slateStatus = slateRepository.findById(game.getSlateId()).orElseThrow().getStatus();
        if (slateStatus == SlateStatus.OPEN_MARKET) {
            transition(game.getSlateId(), SlateStatus.CLOSED, trigger);
        } else if (slateStatus.order() < SlateStatus.OPEN_MARKET.order() && !slateStatus.isTerminal()) {
            log.warn("all games in slate {} are FINAL but the slate is {}; not closing", game.getSlateId(), slateStatus);
        }
    }

    @Transactional
    public void haltMarket(UUID marketId, String reason) {
        setMarketStatus(marketId, MarketStatus.HALTED, reason);
    }

    @Transactional
    public void resumeMarket(UUID marketId, String reason) {
        setMarketStatus(marketId, MarketStatus.ACTIVE, reason);
    }

    private void setMarketStatus(UUID marketId, MarketStatus status, String reason) {
        AthleteMarketEntity market = marketRepository.findByIdForUpdate(marketId)
                .orElseThrow(() -> new IllegalArgumentException("unknown market " + marketId));
        if (market.getStatus() != status) {
            if (status == MarketStatus.HALTED) {
                market.halt("manual: " + reason, Instant.now(), null);
            } else {
                market.resume();
            }
            log.info("market {} -> {} ({})", marketId, status, reason);
        }
    }
}
