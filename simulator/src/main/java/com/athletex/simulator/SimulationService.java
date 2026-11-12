package com.athletex.simulator;

import com.athletex.events.EventPayload;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class SimulationService {

    private final EventSink sink;
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "game-session");
        t.setDaemon(true);
        return t;
    });

    public SimulationService(EventSink sink) {
        this.sink = sink;
    }

    public GameSession start(GameSpec spec, double speed) {
        GameSession session = new GameSession(spec, speed, sink);
        GameSession previous = sessions.putIfAbsent(spec.gameId(), session);
        if (previous != null) {
            throw new IllegalStateException("game " + spec.gameId() + " is already being simulated");
        }
        executor.submit(session);
        return session;
    }

    public GameSession inject(String gameId, EventPayload payload) {
        GameSession session = session(gameId);
        session.inject(payload);
        return session;
    }

    public GameSession stop(String gameId) {
        GameSession session = session(gameId);
        session.stop();
        return session;
    }

    public List<GameSession> sessions() {
        return List.copyOf(sessions.values());
    }

    private GameSession session(String gameId) {
        GameSession session = sessions.get(gameId);
        if (session == null) {
            throw new IllegalArgumentException("no simulation for game " + gameId);
        }
        return session;
    }
}
