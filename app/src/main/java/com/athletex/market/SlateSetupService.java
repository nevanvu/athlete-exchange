package com.athletex.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds slates while they are still SCHEDULED (admin/simulator-facing, not user-facing). */
@Service
public class SlateSetupService {

    public static final int DEFAULT_TOTAL_SHARES = 1_000;

    private final SlateRepository slateRepository;
    private final GameRepository gameRepository;
    private final AthleteMarketRepository marketRepository;

    public SlateSetupService(SlateRepository slateRepository,
                             GameRepository gameRepository,
                             AthleteMarketRepository marketRepository) {
        this.slateRepository = slateRepository;
        this.gameRepository = gameRepository;
        this.marketRepository = marketRepository;
    }

    @Transactional
    public SlateEntity createSlate(String name, Instant ipoOpensAt, Instant ipoClosesAt) {
        if (!ipoClosesAt.isAfter(ipoOpensAt)) {
            throw new IllegalArgumentException("ipoClosesAt must be after ipoOpensAt");
        }
        return slateRepository.save(new SlateEntity(name, ipoOpensAt, ipoClosesAt));
    }

    @Transactional
    public GameEntity addGame(UUID slateId, UUID homeTeamId, UUID awayTeamId, Instant startsAt) {
        requireScheduled(slateId);
        return gameRepository.save(new GameEntity(slateId, homeTeamId, awayTeamId, startsAt));
    }

    @Transactional
    public AthleteMarketEntity listMarket(UUID slateId, UUID athleteId, UUID gameId,
                                          BigDecimal projectedFantasyPoints, BigDecimal projectedMinutes) {
        requireScheduled(slateId);
        return marketRepository.save(new AthleteMarketEntity(
                slateId, athleteId, gameId, projectedFantasyPoints, projectedMinutes, DEFAULT_TOTAL_SHARES));
    }

    private void requireScheduled(UUID slateId) {
        SlateEntity slate = slateRepository.findById(slateId)
                .orElseThrow(() -> new IllegalArgumentException("unknown slate " + slateId));
        if (slate.getStatus() != SlateStatus.SCHEDULED) {
            throw new IllegalStateException("slate %s is %s; it can only be built while SCHEDULED"
                    .formatted(slateId, slate.getStatus()));
        }
    }
}
