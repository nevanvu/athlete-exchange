package com.athletex;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

public final class Await {

    private Await() {
    }

    public static void until(Duration timeout, String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + description);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("timed out after " + timeout + " waiting for " + description);
        }
    }
}
