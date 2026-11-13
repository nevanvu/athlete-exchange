package com.athletex.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.auth.UserEntity;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.matching.OrderType;
import com.athletex.matching.Side;
import com.athletex.settlement.AbstractLiveSlateTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

class SocialTest extends AbstractLiveSlateTest {

    @Autowired private FeedItemRepository feed;
    @Autowired private LeaderboardService leaderboards;

    private List<String> headlines(Slate s) {
        return feed.findBySlateIdOrderByOccurredAtDesc(s.slateId, Limit.of(50)).stream()
                .map(FeedItemEntity::getHeadline).toList();
    }

    private void makePublic(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        user.setPortfolioPublic(true);
        userRepository.save(user);
    }

    @Test
    void notableMovesReachTheFeedAndNameOnlyPublicTraders() {
        Slate s = newSlate(40);
        UUID alice = newTrader();
        UUID bob = newTrader();
        s.toIpo();
        ipoService.bid(alice, s.markets.get(0), 1000, 600);
        s.apply(s.line(0, 12, 0, 0, false));
        s.apply(s.clock(GameState.HALFTIME, 2, 0));
        s.openMarket();
        assertThat(headlines(s)).anyMatch(h -> h.matches("The live-\\w+ IPO cleared: 600 shares sold across 1 athlete\\."));

        orderService.place(alice, s.markets.get(0), Side.SELL, OrderType.LIMIT, 1020L, 100);
        orderService.place(bob, s.markets.get(0), Side.BUY, OrderType.LIMIT, 1020L, 100); // $1,020: big
        assertThat(headlines(s)).contains("Anonymous trader bought 100 shares of " + athlete(s) + " at $10.20.");

        makePublic(alice);
        orderService.place(bob, s.markets.get(0), Side.SELL, OrderType.LIMIT, 1010L, 100);
        orderService.place(alice, s.markets.get(0), Side.BUY, OrderType.LIMIT, 1010L, 100);
        String aliceName = userRepository.findById(alice).orElseThrow().getUsername();
        assertThat(headlines(s)).contains(aliceName + " bought 100 shares of " + athlete(s) + " at $10.10.");

        orderService.place(bob, s.markets.get(0), Side.BUY, OrderType.LIMIT, 1010L, 5); // small: not notable
        s.apply(s.injury(0, InjuryStatus.OUT));
        assertThat(headlines(s).getFirst()).isEqualTo(athlete(s) + " halted: injury: athlete ruled OUT.");

        s.apply(s.line(0, 12, 0, 0, true));
        s.apply(s.clock(GameState.FINAL, 4, 0));
        settlementService.settle(s.slateId);
        assertThat(headlines(s).getFirst()).startsWith("live-").contains(" paid out $");
    }

    @Test
    void leaderboardsRankByValueAndProfitAndHidePrivateNames() {
        Slate s = newSlate(40);
        UUID winner = newTrader();
        UUID loser = newTrader();
        s.toIpo();
        ipoService.bid(winner, s.markets.get(0), 1000, 100);
        ipoService.bid(loser, s.markets.get(0), 1000, 100);
        s.openMarket();
        orderService.place(loser, s.markets.get(0), Side.SELL, OrderType.LIMIT, 800L, 100);
        orderService.place(winner, s.markets.get(0), Side.BUY, OrderType.LIMIT, 800L, 100); // loser dumps at $8
        makePublic(winner);

        List<LeaderboardService.Row> slateBoard = leaderboards.slate(s.slateId, loser, 50);
        assertThat(slateBoard).hasSize(2);
        assertThat(slateBoard.get(0).name()).isEqualTo(userRepository.findById(winner).orElseThrow().getUsername());
        assertThat(slateBoard.get(0).profitCents()).as("200 shares marked at $8, cost $1,800").isEqualTo(-20_000);
        assertThat(slateBoard.get(1).profitCents()).as("sold 100 at $8 bought at $10").isEqualTo(-20_000);
        LeaderboardService.Row me = slateBoard.stream().filter(LeaderboardService.Row::you).findFirst().orElseThrow();
        assertThat(me.name()).as("you always see your own name").isEqualTo(userRepository.findById(loser).orElseThrow().getUsername());

        List<LeaderboardService.Row> overall = leaderboards.overall(null, 1000);
        assertThat(overall).isSortedAccordingTo((a, b) -> Long.compare(b.valueCents(), a.valueCents()));
        assertThat(overall).noneMatch(r -> r.name().equals(TraderNames.HOUSE));
        assertThat(overall.stream().filter(r -> r.name().equals(TraderNames.ANONYMOUS)).count()).isPositive();
        LeaderboardService.Row loserRow = leaderboards.overall(loser, 1).stream()
                .filter(LeaderboardService.Row::you).findFirst().orElseThrow();
        assertThat(loserRow.valueCents()).isEqualTo(SEED_CENTS - 100_000 + 80_000);
        assertThat(loserRow.returnFraction()).isEqualTo(-0.02);
    }

    private String athlete(Slate s) {
        return athleteRepository.findById(s.athletes.get(0)).orElseThrow().getName();
    }
}
