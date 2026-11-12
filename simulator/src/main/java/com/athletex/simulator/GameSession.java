package com.athletex.simulator;

import com.athletex.events.EventEnvelope;
import com.athletex.events.EventPayload;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays one engine forward in wall-clock time at a speed factor (60 = a 48-minute game in 48 seconds)
 * and pushes every event to the sink. Injections interleave safely with ticks.
 */
public final class GameSession implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(GameSession.class);

    private final NbaGameEngine engine;
    private final GameSpec spec;
    private final double speed;
    private final EventSink sink;
    private volatile boolean stopped = false;
    private volatile long eventsPublished = 0;

    public GameSession(GameSpec spec, double speed, EventSink sink) {
        this.spec = spec;
        this.engine = new NbaGameEngine(spec);
        this.speed = speed;
        this.sink = sink;
    }

    @Override
    public void run() {
        long tickMillis = Math.max(1, Math.round(NbaGameEngine.TICK_SECONDS * 1000.0 / speed));
        log.info("game {} starting at {}x speed ({} ms per tick)", spec.gameId(), speed, tickMillis);
        try {
            while (!stopped && !engine.isFinished()) {
                List<EventEnvelope> events = engine.tick();
                events.forEach(this::publish);
                Thread.sleep(tickMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("game {} {} after {} events", spec.gameId(), engine.isFinished() ? "finished" : "stopped", eventsPublished);
    }

    public void inject(EventPayload payload) {
        publish(engine.inject(payload));
    }

    public void stop() {
        stopped = true;
    }

    public GameSpec spec() {
        return spec;
    }

    public double speed() {
        return speed;
    }

    public boolean isFinished() {
        return engine.isFinished();
    }

    public int elapsedSeconds() {
        return engine.elapsedSeconds();
    }

    public long eventsPublished() {
        return eventsPublished;
    }

    private void publish(EventEnvelope event) {
        sink.publish(event);
        eventsPublished++;
    }
}
