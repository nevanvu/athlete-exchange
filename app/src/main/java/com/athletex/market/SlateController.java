package com.athletex.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    public record MarketView(UUID id, UUID athleteId, UUID gameId, MarketStatus status,
                             BigDecimal projectedFantasyPoints, int totalShares) {
    }

    public record SlateDetail(UUID id, String name, SlateStatus status, Instant ipoOpensAt, Instant ipoClosesAt,
                              List<GameView> games, List<MarketView> markets) {
    }

    private final SlateRepository slateRepository;
    private final GameRepository gameRepository;
    private final AthleteMarketRepository marketRepository;

    public SlateController(SlateRepository slateRepository,
                           GameRepository gameRepository,
                           AthleteMarketRepository marketRepository) {
        this.slateRepository = slateRepository;
        this.gameRepository = gameRepository;
        this.marketRepository = marketRepository;
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
        List<MarketView> markets = marketRepository.findBySlateId(id).stream()
                .map(m -> new MarketView(m.getId(), m.getAthleteId(), m.getGameId(), m.getStatus(),
                        m.getProjectedFantasyPoints(), m.getTotalShares()))
                .toList();
        return new SlateDetail(slate.getId(), slate.getName(), slate.getStatus(),
                slate.getIpoOpensAt(), slate.getIpoClosesAt(), games, markets);
    }
}
