package com.athletex.ingestion;

import com.athletex.TestcontainersConfiguration;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.Ulid;
import com.athletex.market.AthleteEntity;
import com.athletex.market.AthleteRepository;
import com.athletex.market.GameEntity;
import com.athletex.market.GameRepository;
import com.athletex.market.SlateEntity;
import com.athletex.market.SlateLifecycleService;
import com.athletex.market.SlateRepository;
import com.athletex.market.SlateSetupService;
import com.athletex.market.SlateStatus;
import com.athletex.market.TeamEntity;
import com.athletex.market.TeamRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
abstract class AbstractIngestionTest {

    @Autowired protected EventIngestionService ingestion;
    @Autowired protected GameEventProcessor processor;
    @Autowired protected SlateSetupService setup;
    @Autowired protected SlateLifecycleService lifecycle;
    @Autowired protected TeamRepository teamRepository;
    @Autowired protected AthleteRepository athleteRepository;
    @Autowired protected SlateRepository slateRepository;
    @Autowired protected GameRepository gameRepository;
    @Autowired protected AthleteGameStatsRepository statsRepository;
    @Autowired protected AthleteAvailabilityRepository availabilityRepository;
    @Autowired protected NewsEventRepository newsRepository;
    @Autowired protected GameEventRepository eventRepository;

    /** One slate, one game, two athletes. */
    protected final class Fixture {
        final UUID slateId;
        final UUID gameId;
        final UUID star;
        final UUID bench;
        private long sequence = 0;

        Fixture(boolean openMarket) {
            String tag = UUID.randomUUID().toString().substring(0, 6);
            Instant opens = Instant.now().plus(Duration.ofHours(1));
            SlateEntity slate = setup.createSlate("ingest-" + tag, opens, opens.plusSeconds(1800));
            TeamEntity home = teamRepository.save(new TeamEntity("H" + tag, "Home"));
            TeamEntity away = teamRepository.save(new TeamEntity("A" + tag, "Away"));
            GameEntity game = setup.addGame(slate.getId(), home.getId(), away.getId(), opens.plusSeconds(3600));
            AthleteEntity starAthlete = athleteRepository.save(new AthleteEntity(home.getId(), "Star " + tag, "PG"));
            AthleteEntity benchAthlete = athleteRepository.save(new AthleteEntity(away.getId(), "Bench " + tag, "C"));
            setup.listMarket(slate.getId(), starAthlete.getId(), game.getId(), BigDecimal.valueOf(40), BigDecimal.valueOf(34));
            setup.listMarket(slate.getId(), benchAthlete.getId(), game.getId(), BigDecimal.valueOf(15), BigDecimal.valueOf(18));
            if (openMarket) {
                lifecycle.transition(slate.getId(), SlateStatus.IPO, "test");
                lifecycle.transition(slate.getId(), SlateStatus.OPEN_MARKET, "test");
            }
            this.slateId = slate.getId();
            this.gameId = game.getId();
            this.star = starAthlete.getId();
            this.bench = benchAthlete.getId();
        }

        EventEnvelope next(EventPayload payload) {
            return at(++sequence, payload);
        }

        EventEnvelope at(long sequence, EventPayload payload) {
            return EventEnvelope.create(Ulid.generate(), "test", gameId.toString(), slateId.toString(),
                    sequence, Instant.now(), payload);
        }

        EventPayload.StatLine line(UUID athlete, int pts, int reb, int ast, double minutes, boolean isFinal) {
            return new EventPayload.StatLine(athlete.toString(),
                    Map.of("pts", pts, "reb", reb, "ast", ast, "stl", 0, "blk", 0, "tov", 0), minutes, isFinal);
        }

        EventPayload.GameStatus status(EventPayload.GameState state, int period, int clock) {
            return new EventPayload.GameStatus(state, period, clock);
        }

        SlateStatus slateStatus() {
            return slateRepository.findById(slateId).orElseThrow().getStatus();
        }

        GameEntity game() {
            return gameRepository.findById(gameId).orElseThrow();
        }

        AthleteGameStatsEntity stats(UUID athlete) {
            return statsRepository.findById(new GameAthleteKey(gameId, athlete)).orElseThrow();
        }
    }
}
