package com.athletex.events;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** JSON wire format: snake_case fields, ISO-8601 instants, payload selected by the "type" field. */
public final class EventCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public String encode(EventEnvelope envelope) {
        return mapper.writeValueAsString(envelope);
    }

    public byte[] encodeBytes(EventEnvelope envelope) {
        return encode(envelope).getBytes(StandardCharsets.UTF_8);
    }

    public EventEnvelope decode(String json) {
        return mapper.readValue(json, EventEnvelope.class);
    }

    public EventEnvelope decode(byte[] json) {
        return decode(new String(json, StandardCharsets.UTF_8));
    }
}
