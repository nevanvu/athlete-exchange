package com.athletex.simulator;

import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a whole simulated game as NDJSON without Kafka, for replay fixtures.
 *
 * <pre>./gradlew :simulator:recordGame --args="out.ndjson 7"</pre>
 *
 * The sample game has four athletes with fixed ids, a news item early on and an injury that
 * takes the second athlete out at the start of the third quarter.
 */
public final class RecordGame {

    public static final String GAME_ID = "00000000-0000-0000-0000-0000000000a1";
    public static final String SLATE_ID = "00000000-0000-0000-0000-0000000000b1";
    public static final List<GameSpec.AthleteSpec> ATHLETES = List.of(
            new GameSpec.AthleteSpec("00000000-0000-0000-0000-0000000000c1", 44.0, 36.0),
            new GameSpec.AthleteSpec("00000000-0000-0000-0000-0000000000c2", 31.0, 32.0),
            new GameSpec.AthleteSpec("00000000-0000-0000-0000-0000000000c3", 22.0, 26.0),
            new GameSpec.AthleteSpec("00000000-0000-0000-0000-0000000000c4", 12.0, 15.0));

    private RecordGame() {
    }

    public static List<EventEnvelope> sampleGame(long seed) {
        GameSpec spec = new GameSpec(GAME_ID, SLATE_ID, Instant.parse("2026-11-11T00:00:00Z"), seed, 0.0, ATHLETES);
        NbaGameEngine engine = new NbaGameEngine(spec);
        List<EventEnvelope> events = new ArrayList<>();
        int tick = 0;
        while (!engine.isFinished()) {
            events.addAll(engine.tick());
            tick++;
            if (tick == 20) {
                events.add(engine.inject(new EventPayload.News(ATHLETES.get(0).athleteId(),
                        "Coach says he will play heavy minutes", 0.08, 0.75)));
            }
            if (tick == 50) {
                events.add(engine.inject(new EventPayload.Injury(ATHLETES.get(1).athleteId(),
                        EventPayload.InjuryStatus.OUT, "ankle, will not return", false)));
            }
        }
        return events;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "sample-game.ndjson");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 7L;
        EventCodec codec = new EventCodec();
        List<String> lines = sampleGame(seed).stream().map(codec::encode).toList();
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println("wrote " + lines.size() + " events to " + out.toAbsolutePath());
    }
}
