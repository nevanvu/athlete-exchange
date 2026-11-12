package com.athletex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.EventPayload.GameState;
import com.athletex.events.EventPayload.InjuryStatus;
import com.athletex.market.GameStatus;
import com.athletex.market.SlateStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "athletex.scheduler.enabled=false",
        "athletex.demo-seed.enabled=false",
        // Reorder gaps are released on the next flush; tests drive flushes explicitly.
        "athletex.ingestion.reorder-max-wait-ms=0",
        "athletex.ingestion.reorder-flush-ms=3600000"
})
class EventIngestionTest extends AbstractIngestionTest {

    @Test
    void cumulativeStatLinesAreAppliedOnceAndNeverRegress() {
        Fixture f = new Fixture(true);
        EventEnvelope first = f.next(f.line(f.star, 10, 2, 0, 6.0, false));

        assertThat(ingestion.ingest(first)).isEqualTo(EventIngestionService.Outcome.APPLIED);
        assertThat(f.stats(f.star).getFantasyPoints()).isEqualByComparingTo("12.40");

        assertThat(ingestion.ingest(first)).isEqualTo(EventIngestionService.Outcome.DUPLICATE);

        EventEnvelope second = f.next(f.line(f.star, 14, 3, 2, 9.5, false));
        assertThat(ingestion.ingest(second)).isEqualTo(EventIngestionService.Outcome.APPLIED);
        assertThat(f.stats(f.star).getFantasyPoints()).isEqualByComparingTo("20.60");
        assertThat(f.stats(f.star).getAsOfEventId()).isEqualTo(second.eventId());

        // A different event carrying an older sequence (late delivery) must not roll the line back.
        EventEnvelope late = f.at(1, f.line(f.star, 5, 0, 0, 3.0, false));
        assertThat(ingestion.ingest(late)).isEqualTo(EventIngestionService.Outcome.STALE);
        assertThat(f.stats(f.star).getFantasyPoints()).isEqualByComparingTo("20.60");
        assertThat(f.stats(f.star).getMinutesPlayed()).isEqualByComparingTo("9.5");

        // Every distinct event is kept in the raw log, including the stale one.
        assertThat(eventRepository.countByGameId(f.gameId)).isEqualTo(3);
    }

    @Test
    void lastGameGoingFinalClosesTheSlate() {
        Fixture f = new Fixture(true);
        ingestion.ingest(f.next(f.status(GameState.IN_PROGRESS, 1, 720)));
        ingestion.ingest(f.next(f.status(GameState.IN_PROGRESS, 3, 240)));
        assertThat(f.game().getPeriod()).isEqualTo(3);
        assertThat(f.game().getClockSecondsRemaining()).isEqualTo(240);
        assertThat(f.slateStatus()).isEqualTo(SlateStatus.OPEN_MARKET);

        ingestion.ingest(f.next(f.status(GameState.FINAL, 4, 0)));
        assertThat(f.game().getStatus()).isEqualTo(GameStatus.FINAL);
        assertThat(f.slateStatus()).isEqualTo(SlateStatus.CLOSED);
    }

    @Test
    void finalBeforeTheMarketOpensIsRecordedWithoutBreakingTheTransaction() {
        Fixture f = new Fixture(false);
        EventEnvelope fin = f.next(f.status(GameState.FINAL, 4, 0));

        assertThat(ingestion.ingest(fin)).isEqualTo(EventIngestionService.Outcome.APPLIED);
        assertThat(f.game().getStatus()).isEqualTo(GameStatus.FINAL);
        assertThat(f.slateStatus()).isEqualTo(SlateStatus.SCHEDULED);
        assertThat(eventRepository.existsById(fin.eventId())).isTrue();
    }

    @Test
    void lateGameStatusCannotUndoFinal() {
        Fixture f = new Fixture(true);
        EventEnvelope inProgress = f.next(f.status(GameState.IN_PROGRESS, 4, 30));
        EventEnvelope fin = f.next(f.status(GameState.FINAL, 4, 0));
        ingestion.ingest(fin);
        assertThat(ingestion.ingest(inProgress)).isEqualTo(EventIngestionService.Outcome.STALE);
        assertThat(f.game().getStatus()).isEqualTo(GameStatus.FINAL);
        assertThat(f.game().getClockSecondsRemaining()).isZero();
    }

    @Test
    void injuriesReplaceAvailabilityAndIgnoreOlderReports() {
        Fixture f = new Fixture(true);
        EventEnvelope questionable = f.next(new EventPayload.Injury(f.star.toString(), InjuryStatus.QUESTIONABLE, "ankle", true));
        EventEnvelope out = f.next(new EventPayload.Injury(f.star.toString(), InjuryStatus.OUT, "ankle", false));
        ingestion.ingest(out);
        assertThat(ingestion.ingest(questionable)).isEqualTo(EventIngestionService.Outcome.STALE);

        AthleteAvailabilityEntity availability =
                availabilityRepository.findById(new GameAthleteKey(f.gameId, f.star)).orElseThrow();
        assertThat(availability.getStatus()).isEqualTo(InjuryStatus.OUT);
        assertThat(availability.getAsOfEventId()).isEqualTo(out.eventId());
    }

    @Test
    void newsIsStoredAsSentimentOnly() {
        Fixture f = new Fixture(true);
        ingestion.ingest(f.next(new EventPayload.News(f.bench.toString(), "Trade rumor", -0.08, 0.4)));
        List<NewsEventEntity> news = newsRepository.findByAthleteIdAndOccurredAtAfterOrderByOccurredAtDesc(
                f.bench, Instant.EPOCH);
        assertThat(news).singleElement().satisfies(n -> {
            assertThat(n.getImpact()).isEqualByComparingTo(BigDecimal.valueOf(-0.08));
            assertThat(n.getConfidence()).isEqualByComparingTo(BigDecimal.valueOf(0.4));
        });
        assertThat(statsRepository.findById(new GameAthleteKey(f.gameId, f.bench))).isEmpty();
    }

    @Test
    void eventsForUnknownGamesAreRejectedAndDroppedByTheProcessor() {
        Fixture f = new Fixture(true);
        EventEnvelope stray = EventEnvelope.create("01JCZX8K4R9QW2M5T7N3VB6YH0", "test", UUID.randomUUID().toString(),
                f.slateId.toString(), 1, Instant.now(), f.status(GameState.IN_PROGRESS, 1, 720));
        assertThatThrownBy(() -> ingestion.ingest(stray)).isInstanceOf(IllegalArgumentException.class);
        processor.process(stray);
        assertThat(eventRepository.existsById(stray.eventId())).isFalse();
    }

    @Test
    void shuffledAndDuplicatedDeliveryConvergesToTheInOrderState() {
        Fixture inOrder = new Fixture(true);
        Fixture shuffled = new Fixture(true);
        List<EventEnvelope> orderedStream = gameScript(inOrder);
        List<EventEnvelope> shuffledStream = new ArrayList<>(gameScript(shuffled));

        orderedStream.forEach(processor::process);

        Random random = new Random(42);
        for (int i = 0; i < 15; i++) {
            shuffledStream.add(shuffledStream.get(random.nextInt(shuffledStream.size())));
        }
        Collections.shuffle(shuffledStream, random);
        for (EventEnvelope event : shuffledStream) {
            processor.process(event);
            if (random.nextInt(4) == 0) {
                processor.flushStale(); // a gap timing out mid-stream, as it would in production
            }
        }
        while (processor.bufferedCount() > 0) {
            processor.flushStale();
        }

        assertThat(snapshot(shuffled)).isEqualTo(snapshot(inOrder));
        assertThat(eventRepository.countByGameId(shuffled.gameId)).isEqualTo(orderedStream.size());
        assertThat(shuffled.slateStatus()).isEqualTo(SlateStatus.CLOSED);
    }

    /** A compact deterministic game: clock ticks, growing stat lines, an injury, news, finals. */
    private static List<EventEnvelope> gameScript(Fixture f) {
        List<EventEnvelope> events = new ArrayList<>();
        events.add(f.next(f.status(GameState.IN_PROGRESS, 1, 720)));
        for (int i = 1; i <= 12; i++) {
            events.add(f.next(f.line(f.star, 2 * i, i / 2, i / 3, 3.0 * i, false)));
            if (i % 3 == 0) {
                events.add(f.next(f.line(f.bench, i, i / 3, 0, 1.5 * i, false)));
                events.add(f.next(f.status(GameState.IN_PROGRESS, 1 + i / 4, 720 - 60 * (i % 4))));
            }
            if (i == 7) {
                events.add(f.next(new EventPayload.Injury(f.bench.toString(), InjuryStatus.OUT, "knee", false)));
                events.add(f.next(new EventPayload.News(f.star.toString(), "Extension talks", 0.05, 0.6)));
            }
        }
        events.add(f.next(f.line(f.star, 26, 6, 4, 36.0, true)));
        events.add(f.next(f.line(f.bench, 9, 3, 0, 13.5, true)));
        events.add(f.next(f.status(GameState.FINAL, 4, 0)));
        return events;
    }

    private record Snapshot(String starPoints, String starMinutes, boolean starFinal, long starSeq,
                            String benchPoints, boolean benchFinal, InjuryStatus benchAvailability,
                            GameStatus gameStatus, int period, int clock, long statusSequence,
                            SlateStatus slate, int news) {
    }

    private Snapshot snapshot(Fixture f) {
        AthleteGameStatsEntity star = f.stats(f.star);
        AthleteGameStatsEntity bench = f.stats(f.bench);
        var game = f.game();
        return new Snapshot(star.getFantasyPoints().toPlainString(), star.getMinutesPlayed().toPlainString(),
                star.isFinal(), star.getAsOfSequence(),
                bench.getFantasyPoints().toPlainString(), bench.isFinal(),
                availabilityRepository.findById(new GameAthleteKey(f.gameId, f.bench)).orElseThrow().getStatus(),
                game.getStatus(), game.getPeriod(), game.getClockSecondsRemaining(), game.getStatusSequence(),
                f.slateStatus(),
                newsRepository.findByAthleteIdAndOccurredAtAfterOrderByOccurredAtDesc(f.star, Instant.EPOCH).size());
    }
}
