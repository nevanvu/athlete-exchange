package com.athletex.trading;

import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountRepository;
import com.athletex.ledger.AccountType;
import com.athletex.market.AthleteMarketRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    public record PositionView(UUID marketId, UUID athleteId, int quantity, int lockedQuantity,
                               long averageCostCents, Long lastPriceCents,
                               long marketValueCents, long unrealizedPnlCents, long realizedPnlCents) {
    }

    public record PortfolioView(long cashCents, long escrowCents, long positionsValueCents,
                                long totalEquityCents, long totalRealizedPnlCents, List<PositionView> positions) {
    }

    private final PositionRepository positionRepository;
    private final AthleteMarketRepository marketRepository;
    private final AccountRepository accountRepository;

    public PortfolioController(PositionRepository positionRepository, AthleteMarketRepository marketRepository,
                               AccountRepository accountRepository) {
        this.positionRepository = positionRepository;
        this.marketRepository = marketRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public PortfolioView portfolio(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<PositionView> positions = positionRepository.findByUserIdAndQuantityGreaterThan(userId, 0).stream()
                .map(p -> {
                    var market = marketRepository.findById(p.getMarketId()).orElseThrow();
                    Long last = market.getLastTradePriceCents();
                    long value = last == null ? p.getTotalCostCents() : last * p.getQuantity();
                    return new PositionView(p.getMarketId(), market.getAthleteId(), p.getQuantity(),
                            p.getLockedQuantity(), p.averageCostCents(), last, value,
                            value - p.getTotalCostCents(), p.getRealizedPnlCents());
                })
                .toList();
        long cash = balance(userId, AccountType.USER_CASH);
        long escrow = balance(userId, AccountType.USER_ESCROW);
        long positionsValue = positions.stream().mapToLong(PositionView::marketValueCents).sum();
        long realized = positionRepository.findByUserId(userId).stream()
                .mapToLong(PositionEntity::getRealizedPnlCents).sum();
        return new PortfolioView(cash, escrow, positionsValue, cash + escrow + positionsValue, realized, positions);
    }

    private long balance(UUID userId, AccountType type) {
        return accountRepository.findByOwnerUserIdAndType(userId, type).map(AccountEntity::getBalanceCents).orElse(0L);
    }
}
