package com.athletex.simulator;

import com.athletex.events.EventCodec;
import com.athletex.events.EventEnvelope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to the game-events topic keyed by game id (so a game's events stay ordered on one
 * partition) and, when a record directory is configured, appends NDJSON files for replay.
 */
@Component
public class KafkaEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSink.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec codec = new EventCodec();
    private final String topic;
    private final Path recordDir;

    public KafkaEventSink(KafkaTemplate<String, String> kafkaTemplate,
                          @Value("${athletex.simulator.topic:game-events}") String topic,
                          @Value("${athletex.simulator.record-dir:}") String recordDir) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.recordDir = recordDir.isBlank() ? null : Path.of(recordDir);
    }

    @Override
    public void publish(EventEnvelope event) {
        String json = codec.encode(event);
        kafkaTemplate.send(topic, event.gameId(), json);
        if (recordDir != null) {
            record(event.gameId(), json);
        }
    }

    private void record(String gameId, String json) {
        try {
            Files.createDirectories(recordDir);
            Files.writeString(recordDir.resolve(gameId + ".ndjson"), json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("could not record event for game {}: {}", gameId, e.getMessage());
        }
    }
}
