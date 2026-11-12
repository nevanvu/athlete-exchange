package com.athletex.simulator;

import com.athletex.events.EventEnvelope;

/** Where simulated events go: Kafka in the app, an in-memory list in tests, optionally a recording file. */
public interface EventSink {

    void publish(EventEnvelope event);
}
