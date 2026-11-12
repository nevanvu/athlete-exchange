package com.athletex.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox: messages are written in the same transaction as the domain change
 * and relayed to Kafka afterwards, so a publish can never be lost or precede its commit.
 */
@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final JsonMapper json;

    public OutboxService(OutboxRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public OutboxEntity enqueue(String topic, String key, Object message) {
        return repository.save(new OutboxEntity(topic, key, json.writeValueAsString(message)));
    }
}
