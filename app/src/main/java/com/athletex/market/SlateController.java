package com.athletex.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/slates")
public class SlateController {

    public record SlateSummary(UUID id, String name, SlateStatus status, Instant ipoOpensAt, Instant ipoClosesAt) {
    }

    public record GameView(UUID id, UUID homeTeamId, UUID awayTeamId, GameStatus status, Instant startsAt) {
    }

    public record MarketView(UUID id, UUID athleteId, String athleteName, String position, String teamCode,
                             UUID gameId, MarketStatus status, BigDecimal projectedFantasyPoints,
                             long reservePriceCents, int totalShares,
                             Long ipoClearingPriceCents, Long lastTradePriceCents,
                             Long fairValueCents, Long settlementValueCents) {
    }

    public record SlateDetail(UUID id, String name, SlateStatus status, Instant ipoOpensAt, Instant ipoClosesAt,
                              List<GameView> games, List<MarketView> markets) {
    }

    private final SlateRepository slateRepository;
    private final GameRepository gameRepository;
    private final AthleteMarketRepository marketRepository;
    private final AthleteRepository athleteRepository;
    private final TeamRepository teamRepository;

    public SlateController(SlateRepository slateRepository,
                           GameRepository gameRepository,
                           AthleteMarketRepository marketRepository,
                           AthleteRepository athleteRepository,
                           TeamRepository teamRepository) {
        this.slateRepository = slateRepository;
        this.gameRepository = gameRepository;
        this.marketRepository = marketRepository;
        this.athleteRepository = athleteRepository;
        this.teamRepository = teamRepository;
    }

    @GetMapping
    public List<SlateSummary> slates() {
        return slateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(s -> new SlateSummary(s.getId(), s.getName(), s.getStatus(), s.getIpoOpensAt(), s.getIpoClosesAt()))
                .toList();
    }

    @GetMapping("/{id}")
    public SlateDetail slate(@PathVariable UUID id) {
        SlateEntity slate = slateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown slate " + id));
        List<GameView> games = gameRepository.findBySlateId(id).stream()
                .map(g -> new GameView(g.getId(), g.getHomeTeamId(), g.getAwayTeamId(), g.getStatus(), g.getStartsAt()))
                .toList();
        List<AthleteMarketEntity> marketEntities = marketRepository.findBySlateId(id);
        Map<UUID, AthleteEntity> athletes = athleteRepository
                .findAllById(marketEntities.stream().map(AthleteMarketEntity::getAthleteId).toList()).stream()
                .collect(Collectors.toMap(AthleteEntity::getId, a -> a));
        Map<UUID, TeamEntity> teams = teamRepository
                .findAllById(athletes.values().stream().map(AthleteEntity::getTeamId).distinct().toList()).stream()
                .collect(Collectors.toMap(TeamEntity::getId, t -> t));
        List<MarketView> markets = marketEntities.stream()
                .map(m -> {
                    AthleteEntity athlete = athletes.get(m.getAthleteId());
                    TeamEntity team = athlete == null ? null : teams.get(athlete.getTeamId());
                    return new MarketView(m.getId(), m.getAthleteId(),
                            athlete == null ? null : athlete.getName(),
                            athlete == null ? null : athlete.getPosition(),
                            team == null ? null : team.getCode(),
                            m.getGameId(), m.getStatus(), m.getProjectedFantasyPoints(),
                            Pricing.reservePriceCents(m.getProjectedFantasyPoints()), m.getTotalShares(),
                            m.getIpoClearingPriceCents(), m.getLastTradePriceCents(),
                            m.getFairValueCents(), m.getSettlementValueCents());
                })
                .toList();
        return new SlateDetail(slate.getId(), slate.getName(), slate.getStatus(),
                slate.getIpoOpensAt(), slate.getIpoClosesAt(), games, markets);
    }
}
