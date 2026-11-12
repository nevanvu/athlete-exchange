package com.athletex.settlement;

import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettlementController {

    public record MarketSettlement(UUID marketId, UUID athleteId, BigDecimal fantasyPoints, Long valueCents) {
    }

    public record CorrectionView(UUID marketId, String eventId, long oldValueCents, long newValueCents,
                                 long totalAdjustmentCents, boolean applied, String reason, Instant createdAt) {
    }

    public record SettlementView(UUID slateId, SlateStatus status, Instant settledAt, Long totalPaidCents,
                                 List<MarketSettlement> markets, List<CorrectionView> corrections) {
    }

    private final SlateRepository slateRepository;
    private final AthleteMarketRepository marketRepository;
    private final SettlementRunRepository runRepository;
    private final SettlementCorrectionRepository correctionRepository;

    public SettlementController(SlateRepository slateRepository, AthleteMarketRepository marketRepository,
                                SettlementRunRepository runRepository,
                                SettlementCorrectionRepository correctionRepository) {
        this.slateRepository = slateRepository;
        this.marketRepository = marketRepository;
        this.runRepository = runRepository;
        this.correctionRepository = correctionRepository;
    }

    @GetMapping("/api/slates/{id}/settlement")
    public SettlementView settlement(@PathVariable UUID id) {
        SlateStatus status = slateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown slate " + id)).getStatus();
        List<AthleteMarketEntity> markets = marketRepository.findBySlateId(id);
        var run = runRepository.findBySlateId(id);
        return new SettlementView(id, status,
                run.map(SettlementRunEntity::getSettledAt).orElse(null),
                run.map(SettlementRunEntity::getTotalPaidCents).orElse(null),
                markets.stream().map(m -> new MarketSettlement(m.getId(), m.getAthleteId(),
                        m.getSettlementFantasyPoints(), m.getSettlementValueCents())).toList(),
                correctionRepository.findByMarketIdInOrderByCreatedAtAsc(
                                markets.stream().map(AthleteMarketEntity::getId).toList()).stream()
                        .map(c -> new CorrectionView(c.getMarketId(), c.getEventId(), c.getOldValueCents(),
                                c.getNewValueCents(), c.getTotalAdjustmentCents(), c.isApplied(), c.getReason(),
                                c.getCreatedAt()))
                        .toList());
    }
}
