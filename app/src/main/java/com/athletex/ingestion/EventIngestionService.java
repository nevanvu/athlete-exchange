package com.athletex.ingestion;

import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.NbaFantasyScoring;
import com.athletex.market.GameEntity;
import com.athletex.market.GameRepository;
import com.athletex.market.GameStatus;
import com.athletex.market.SlateLifecycleService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies one game event to the domain, exactly once: the raw event is stored under its id
 * (duplicates are skipped), and cumulative payloads only replace state carrying an older
 * sequence, so redelivered or out-of-order events cannot regress it.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    public enum Outcome {
        APPLIED, DUPLICATE, STALE
    }

    private final GameEventRepository eventRepository;
    private final GameRepository gameRepository;
    private final AthleteGameStatsRepository statsRepository;
    private final AthleteAvailabilityRepository availabilityRepository;
    private final NewsEventRepository newsRepository;
    private final SlateLifecycleService lifecycle;
    private final EventCodec codec = new EventCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    public EventIngestionService(GameEventRepository eventRepository,
                                 GameRepository gameRepository,
                                 AthleteGameStatsRepository statsRepository,
                                 AthleteAvailabilityRepository availabilityRepository,
                                 NewsEventRepository newsRepository,
                                 SlateLifecycleService lifecycle) {
        this.eventRepository = eventRepository;
        this.gameRepository = gameRepository;
        this.statsRepository = statsRepository;
        this.availabilityRepository = availabilityRepository;
        this.newsRepository = newsRepository;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Outcome ingest(EventEnvelope event) {
        if (eventRepository.existsById(event.eventId())) {
            return Outcome.DUPLICATE;
        }
        UUID gameId = UUID.fromString(event.gameId());
        GameEntity game = gameRepository.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("event " + event.eventId() + " for unknown game " + gameId));
        UUID slateId = event.slateId() == null ? null : UUID.fromString(event.slateId());

        eventRepository.save(new GameEventEntity(event.eventId(), gameId, slateId, event.type().wireName(),
                event.sequence(), event.source(), event.occurredAt(), event.corrects(), codec.encode(event)));

        Outcome outcome = switch (event.payload()) {
            case EventPayload.GameStatus status -> applyGameStatus(game, status, event);
            case EventPayload.StatLine line -> applyStatLine(gameId, line, event);
            case EventPayload.Injury injury -> applyInjury(gameId, injury, event);
            case EventPayload.News news -> applyNews(gameId, news, event);
        };
        log.debug("event {} ({} seq {}) {}", event.eventId(), event.type(), event.sequence(), outcome);
        return outcome;
    }

    private Outcome applyGameStatus(GameEntity game, EventPayload.GameStatus status, EventEnvelope event) {
        if (event.sequence() <= game.getStatusSequence()) {
            return Outcome.STALE;
        }
        game.updateClock(status.period(), status.clockSecondsRemaining(), event.sequence());
        lifecycle.recordGameStatus(game.getId(), GameStatus.valueOf(status.status().name()), "event:" + event.eventId());
        return Outcome.APPLIED;
    }

    private Outcome applyStatLine(UUID gameId, EventPayload.StatLine line, EventEnvelope event) {
        GameAthleteKey key = new GameAthleteKey(gameId, UUID.fromString(line.athleteId()));
        AthleteGameStatsEntity stats = statsRepository.findById(key)
                .orElseGet(() -> statsRepository.save(new AthleteGameStatsEntity(key)));
        if (event.sequence() <= stats.getAsOfSequence()) {
            return Outcome.STALE;
        }
        stats.replace(json.writeValueAsString(line.stats()),
                BigDecimal.valueOf(line.minutesPlayed()).setScale(1, RoundingMode.HALF_UP),
                BigDecimal.valueOf(NbaFantasyScoring.fantasyPoints(line.stats())).setScale(2, RoundingMode.HALF_UP),
                line.isFinal(), event.sequence(), event.eventId());
        return Outcome.APPLIED;
    }

    private Outcome applyInjury(UUID gameId, EventPayload.Injury injury, EventEnvelope event) {
        GameAthleteKey key = new GameAthleteKey(gameId, UUID.fromString(injury.athleteId()));
        AthleteAvailabilityEntity availability = availabilityRepository.findById(key)
                .orElseGet(() -> availabilityRepository.save(new AthleteAvailabilityEntity(key)));
        if (event.sequence() <= availability.getAsOfSequence()) {
            return Outcome.STALE;
        }
        availability.replace(injury.status(), injury.detail(), event.sequence(), event.eventId());
        return Outcome.APPLIED;
    }

    private Outcome applyNews(UUID gameId, EventPayload.News news, EventEnvelope event) {
        newsRepository.save(new NewsEventEntity(event.eventId(), gameId, UUID.fromString(news.athleteId()),
                news.headline(), BigDecimal.valueOf(news.impact()), BigDecimal.valueOf(news.confidence()),
                event.occurredAt()));
        return Outcome.APPLIED;
    }
}
