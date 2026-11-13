package com.athletex.social;

import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountRepository;
import com.athletex.market.AthleteMarketEntity;
import com.athletex.market.AthleteMarketRepository;
import com.athletex.trading.PositionEntity;
import com.athletex.trading.PositionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rankings by account value (overall) and by profit within one slate. Open positions are marked at
 * the last trade price. The house is not ranked; private traders appear as "Anonymous trader".
 * Computed on request, which is fine at hobby scale; a materialized view would replace it at scale.
 */
@Service
public class LeaderboardService {

    public record Row(int rank, String name, long valueCents, long profitCents, double returnFraction, boolean you) {
    }

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final AthleteMarketRepository marketRepository;
    private final long startingCents;

    public LeaderboardService(UserRepository userRepository, AccountRepository accountRepository,
                              PositionRepository positionRepository, AthleteMarketRepository marketRepository,
                              @Value("${athletex.seed-balance-cents}") long startingCents) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.marketRepository = marketRepository;
        this.startingCents = startingCents;
    }

    @Transactional(readOnly = true)
    public List<Row> overall(UUID viewer, int limit) {
        Map<UUID, Long> cash = new HashMap<>();
        for (AccountEntity account : accountRepository.findAll()) {
            if (account.getOwnerUserId() != null) {
                cash.merge(account.getOwnerUserId(), account.getBalanceCents(), Long::sum);
            }
        }
        Map<UUID, AthleteMarketEntity> markets = marketRepository.findAll().stream()
                .collect(Collectors.toMap(AthleteMarketEntity::getId, m -> m));
        Map<UUID, Long> holdings = new HashMap<>();
        for (PositionEntity p : positionRepository.findAll()) {
            if (p.getQuantity() > 0) {
                holdings.merge(p.getUserId(), markValue(p, markets.get(p.getMarketId())), Long::sum);
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (UserEntity user : userRepository.findAll()) {
            if (user.getId().equals(PositionEntity.HOUSE_USER_ID)) {
                continue;
            }
            long value = cash.getOrDefault(user.getId(), 0L) + holdings.getOrDefault(user.getId(), 0L);
            entries.add(new Entry(user, value, value - startingCents));
        }
        return rank(entries, viewer, limit, startingCents);
    }

    /** Profit from one slate's markets: realized, plus open shares marked at the last price. */
    @Transactional(readOnly = true)
    public List<Row> slate(UUID slateId, UUID viewer, int limit) {
        Map<UUID, AthleteMarketEntity> markets = marketRepository.findBySlateId(slateId).stream()
                .collect(Collectors.toMap(AthleteMarketEntity::getId, m -> m));
        Map<UUID, Long> profit = new HashMap<>();
        Map<UUID, Long> invested = new HashMap<>();
        for (UUID marketId : markets.keySet()) {
            for (PositionEntity p : positionRepository.findByMarketId(marketId)) {
                if (p.getUserId().equals(PositionEntity.HOUSE_USER_ID)) {
                    continue;
                }
                long open = p.getQuantity() > 0 ? markValue(p, markets.get(marketId)) - p.getTotalCostCents() : 0;
                profit.merge(p.getUserId(), p.getRealizedPnlCents() + open, Long::sum);
                invested.merge(p.getUserId(), p.getTotalCostCents(), Long::sum);
            }
        }
        Set<UUID> traders = profit.keySet();
        List<Entry> entries = userRepository.findAllById(traders).stream()
                .map(u -> new Entry(u, profit.get(u.getId()), profit.get(u.getId())))
                .toList();
        return rank(new ArrayList<>(entries), viewer, limit, startingCents);
    }

    private record Entry(UserEntity user, long valueCents, long profitCents) {
    }

    private static List<Row> rank(List<Entry> entries, UUID viewer, int limit, long startingCents) {
        entries.sort(Comparator.comparingLong(Entry::valueCents).reversed()
                .thenComparing(e -> e.user().getCreatedAt()));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean you = e.user().getId().equals(viewer);
            if (i >= limit && !you) {
                continue; // always include the viewer's own row, wherever they rank
            }
            String name = e.user().isPortfolioPublic() || you ? e.user().getUsername() : TraderNames.ANONYMOUS;
            rows.add(new Row(i + 1, name, e.valueCents(), e.profitCents(),
                    (double) e.profitCents() / startingCents, you));
        }
        return rows;
    }

    private static long markValue(PositionEntity p, AthleteMarketEntity market) {
        Long price = market == null ? null : market.getSettlementValueCents() != null
                ? market.getSettlementValueCents() : market.getLastTradePriceCents();
        return price == null ? p.getTotalCostCents() : price * p.getQuantity();
    }
}
