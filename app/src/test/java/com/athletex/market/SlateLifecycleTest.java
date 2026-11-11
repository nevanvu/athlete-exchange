package com.athletex.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class SlateLifecycleTest {

    @Autowired
    private SlateLifecycleService lifecycle;
    @Autowired
    private SlateSetupService setup;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private AthleteRepository athleteRepository;
    @Autowired
    private SlateRepository slateRepository;
    @Autowired
    private SlateTransitionRepository transitionRepository;

    private record Fixture(SlateEntity slate, List<GameEntity> games, List<AthleteMarketEntity> markets) {
    }

    private Fixture slateWith(int gameCount, int marketsPerGame, Instant ipoOpensAt, Instant ipoClosesAt) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        SlateEntity slate = setup.createSlate("slate-" + tag, ipoOpensAt, ipoClosesAt);
        List<GameEntity> games = new ArrayList<>();
        List<AthleteMarketEntity> markets = new ArrayList<>();
        for (int g = 0; g < gameCount; g++) {
            TeamEntity home = teamRepository.save(new TeamEntity("H" + tag.substring(0, 4) + g, "Home " + g));
            TeamEntity away = teamRepository.save(new TeamEntity("A" + tag.substring(0, 4) + g, "Away " + g));
            GameEntity game = setup.addGame(slate.getId(), home.getId(), away.getId(), ipoClosesAt.plusSeconds(600));
            games.add(game);
            for (int m = 0; m < marketsPerGame; m++) {
                AthleteEntity athlete = athleteRepository.save(
                        new AthleteEntity(home.getId(), "Player " + tag + "-" + g + "-" + m, "PG"));
                markets.add(setup.listMarket(slate.getId(), athlete.getId(), game.getId(),
                        BigDecimal.valueOf(30), BigDecimal.valueOf(32)));
            }
        }
        return new Fixture(slate, games, markets);
    }

    private Fixture futureSlate(int gameCount, int marketsPerGame) {
        Instant opens = Instant.now().plus(Duration.ofHours(1));
        return slateWith(gameCount, marketsPerGame, opens, opens.plus(Duration.ofMinutes(30)));
    }

    private SlateStatus statusOf(UUID slateId) {
        return slateRepository.findById(slateId).orElseThrow().getStatus();
    }

    @Test
    void happyPathReachesClosedWhenLastGameGoesFinal() {
        Fixture f = futureSlate(2, 2);
        UUID slateId = f.slate().getId();

        assertThat(lifecycle.transition(slateId, SlateStatus.IPO, "test")).isTrue();
        assertThat(lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "test")).isTrue();

        lifecycle.recordGameStatus(f.games().get(0).getId(), GameStatus.FINAL, "game0_final");
        assertThat(statusOf(slateId)).isEqualTo(SlateStatus.OPEN_MARKET);

        lifecycle.recordGameStatus(f.games().get(1).getId(), GameStatus.FINAL, "game1_final");
        assertThat(statusOf(slateId)).isEqualTo(SlateStatus.CLOSED);

        List<SlateTransitionEntity> audit = transitionRepository.findBySlateIdOrderByOccurredAt(slateId);
        assertThat(audit).extracting(SlateTransitionEntity::getToStatus)
                .containsExactly(SlateStatus.IPO, SlateStatus.OPEN_MARKET, SlateStatus.CLOSED);
    }

    @Test
    void redeliveredTriggerIsIdempotentNoOp() {
        Fixture f = futureSlate(1, 1);
        UUID slateId = f.slate().getId();
        lifecycle.transition(slateId, SlateStatus.IPO, "test");
        lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "test");

        assertThat(lifecycle.transition(slateId, SlateStatus.IPO, "redelivered")).isFalse();
        assertThat(lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "redelivered")).isFalse();
        assertThat(statusOf(slateId)).isEqualTo(SlateStatus.OPEN_MARKET);
        assertThat(transitionRepository.findBySlateIdOrderByOccurredAt(slateId)).hasSize(2);
    }

    @Test
    void cannotSkipStates() {
        Fixture f = futureSlate(1, 1);
        assertThatThrownBy(() -> lifecycle.transition(f.slate().getId(), SlateStatus.OPEN_MARKET, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skip");
    }

    @Test
    void ipoRequiresAtLeastOneMarket() {
        Instant opens = Instant.now().plus(Duration.ofHours(1));
        SlateEntity empty = setup.createSlate("empty", opens, opens.plusSeconds(1800));
        assertThatThrownBy(() -> lifecycle.transition(empty.getId(), SlateStatus.IPO, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no athlete markets");
    }

    @Test
    void cannotCloseWithUnfinishedGames() {
        Fixture f = futureSlate(2, 1);
        UUID slateId = f.slate().getId();
        lifecycle.transition(slateId, SlateStatus.IPO, "test");
        lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "test");
        lifecycle.recordGameStatus(f.games().get(0).getId(), GameStatus.FINAL, "test");

        assertThatThrownBy(() -> lifecycle.transition(slateId, SlateStatus.CLOSED, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unfinished");
    }

    @Test
    void voidIsReachableFromAnyNonTerminalStateAndIsTerminal() {
        Fixture f = futureSlate(1, 1);
        UUID slateId = f.slate().getId();
        lifecycle.transition(slateId, SlateStatus.IPO, "test");

        assertThat(lifecycle.transition(slateId, SlateStatus.VOIDED, "mass_postponement")).isTrue();
        assertThat(statusOf(slateId)).isEqualTo(SlateStatus.VOIDED);
        assertThatThrownBy(() -> lifecycle.transition(slateId, SlateStatus.OPEN_MARKET, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void schedulerAdvancesDueSlates() {
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        Fixture due = slateWith(1, 1, past, past.plusSeconds(60));

        lifecycle.advanceDueSlates(Instant.now());

        // Both windows are in the past, so one sweep takes it through IPO to OPEN_MARKET.
        assertThat(statusOf(due.slate().getId())).isEqualTo(SlateStatus.OPEN_MARKET);

        Fixture notDue = futureSlate(1, 1);
        lifecycle.advanceDueSlates(Instant.now());
        assertThat(statusOf(notDue.slate().getId())).isEqualTo(SlateStatus.SCHEDULED);
    }

    @Test
    void slateCannotBeBuiltAfterLeavingScheduled() {
        Fixture f = futureSlate(1, 1);
        lifecycle.transition(f.slate().getId(), SlateStatus.IPO, "test");
        TeamEntity team = teamRepository.save(new TeamEntity("X" + UUID.randomUUID().toString().substring(0, 6), "X"));
        assertThatThrownBy(() -> setup.addGame(f.slate().getId(), team.getId(), team.getId(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCHEDULED");
    }

    @Test
    void haltAndResumeMarket() {
        Fixture f = futureSlate(1, 1);
        UUID marketId = f.markets().get(0).getId();

        lifecycle.haltMarket(marketId, "injury");
        assertThat(marketStatus(marketId)).isEqualTo(MarketStatus.HALTED);

        lifecycle.resumeMarket(marketId, "fair value republished");
        assertThat(marketStatus(marketId)).isEqualTo(MarketStatus.ACTIVE);
    }

    @Autowired
    private AthleteMarketRepository athleteMarketRepository;

    private MarketStatus marketStatus(UUID marketId) {
        return athleteMarketRepository.findById(marketId).orElseThrow().getStatus();
    }
}
