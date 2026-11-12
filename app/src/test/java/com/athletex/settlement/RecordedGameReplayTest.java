package com.athletex.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import com.athletex.events.NbaFantasyScoring;
import com.athletex.events.Ulid;
import com.athletex.fairvalue.FairValueTickEntity;
import com.athletex.market.Pricing;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays a game recorded by the simulator (src/test/resources/replays/sample-game.ndjson,
 * regenerate with ./gradlew :simulator:recordGame) through ingestion, fair value and settlement.
 */
class RecordedGameReplayTest extends AbstractLiveSlateTest {

    /** Athlete ids and projections exactly as recorded (see RecordGame.ATHLETES). */
    private static final List<String> RECORDED_ATHLETES = List.of(
            "00000000-0000-0000-0000-0000000000c1", "00000000-0000-0000-0000-0000000000c2",
            "00000000-0000-0000-0000-0000000000c3", "00000000-0000-0000-0000-0000000000c4");
    private static final double[] PROJECTIONS = {44, 31, 22, 12};

    private static List<EventEnvelope> recording;

    @BeforeAll
    static void loadRecording() throws IOException {
        EventCodec codec = new EventCodec();
        try (InputStream in = RecordedGameReplayTest.class.getResourceAsStream("/replays/sample-game.ndjson")) {
            recording = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank()).map(codec::decode).toList();
        }
    }

    @Test
    void inOrderReplayIsDeterministicAndSettlesToTheRecordedBoxScore() {
        Slate first = newSlate(PROJECTIONS);
        Slate second = newSlate(PROJECTIONS);
        for (Slate s : List.of(first, second)) {
            s.openMarket();
            remap(s).forEach(ingestion::ingest);
            assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.SETTLED);
        }

        // Same events in, same fair value path out, tick for tick.
        assertThat(fairValuePaths(second)).isEqualTo(fairValuePaths(first));
        assertThat(settlementValues(first)).containsExactlyElementsOf(expectedSettlementValues());
        assertThat(settlementValues(second)).containsExactlyElementsOf(expectedSettlementValues());

        // The recorded injury (athlete 2, start of the 3rd quarter) shows up as availability 0.
        List<FairValueTickEntity> injured = tickRepository.findByMarketIdOrderByIdAsc(first.markets.get(1));
        assertThat(injured).anySatisfy(t -> assertThat(t.getAvailability()).isEqualByComparingTo("0"));
        // Fair value rose and fell during the game rather than sitting still.
        assertThat(fairValuePaths(first).get(0).size()).isGreaterThan(20);
    }

    @Test
    void shuffledDuplicatedReplayConvergesToTheSameSettlement() {
        Slate s = newSlate(PROJECTIONS);
        s.openMarket();
        List<EventEnvelope> events = new ArrayList<>(remap(s));
        Random random = new Random(2026);
        for (int i = 0; i < 40; i++) {
            events.add(events.get(random.nextInt(events.size())));
        }
        Collections.shuffle(events, random);
        for (EventEnvelope event : events) {
            processor.process(event);
            if (random.nextInt(5) == 0) {
                processor.flushStale();
            }
        }
        while (processor.bufferedCount() > 0) {
            processor.flushStale();
        }

        assertThat(settlementService.settle(s.slateId).outcome()).isEqualTo(SettlementService.Outcome.SETTLED);
        assertThat(settlementValues(s)).containsExactlyElementsOf(expectedSettlementValues());
        assertThat(allBalances()).isZero();
    }

    /** The recording with this slate's game, slate and athlete ids, and fresh event ids. */
    private List<EventEnvelope> remap(Slate s) {
        Map<String, String> athletes = new java.util.HashMap<>();
        for (int i = 0; i < RECORDED_ATHLETES.size(); i++) {
            athletes.put(RECORDED_ATHLETES.get(i), s.athletes.get(i).toString());
        }
        List<EventEnvelope> out = new ArrayList<>();
        for (EventEnvelope e : recording) {
            EventPayload payload = switch (e.payload()) {
                case EventPayload.StatLine l ->
                        new EventPayload.StatLine(athletes.get(l.athleteId()), l.stats(), l.minutesPlayed(), l.isFinal());
                case EventPayload.Injury i ->
                        new EventPayload.Injury(athletes.get(i.athleteId()), i.status(), i.detail(), i.returningThisGame());
                case EventPayload.News n ->
                        new EventPayload.News(athletes.get(n.athleteId()), n.headline(), n.impact(), n.confidence());
                case EventPayload.GameStatus g -> g;
            };
            out.add(new EventEnvelope(Ulid.generate(), e.schemaVersion(), e.source(), s.gameId.toString(),
                    s.slateId.toString(), e.sequence(), e.occurredAt(), null, null, payload));
        }
        return out;
    }

    private List<List<Long>> fairValuePaths(Slate s) {
        return s.markets.stream()
                .map(m -> tickRepository.findByMarketIdOrderByIdAsc(m).stream()
                        .map(FairValueTickEntity::getFairValueCents).toList())
                .toList();
    }

    private List<Long> settlementValues(Slate s) {
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < s.markets.size(); i++) {
            values.add(s.market(i).getSettlementValueCents());
        }
        return values;
    }

    private static List<Long> expectedSettlementValues() {
        List<Long> values = new ArrayList<>();
        for (String athlete : RECORDED_ATHLETES) {
            EventPayload.StatLine finalLine = recording.stream()
                    .map(EventEnvelope::payload)
                    .filter(p -> p instanceof EventPayload.StatLine l && l.athleteId().equals(athlete) && l.isFinal())
                    .map(p -> (EventPayload.StatLine) p)
                    .findFirst().orElseThrow();
            BigDecimal points = BigDecimal.valueOf(NbaFantasyScoring.fantasyPoints(finalLine.stats()))
                    .setScale(2, RoundingMode.HALF_UP);
            values.add(Pricing.settlementValueCents(points));
        }
        return values;
    }
}
