package com.athletex.market;

import java.util.UUID;

/**
 * Extension points other modules plug into the slate state machine. Implemented by the
 * trading module (IPO clearing, order cancellation); the lifecycle service stays unaware of it.
 */
public interface SlateLifecycleHooks {

    /** Runs inside every applied transition's transaction, after the status flips. */
    default void onTransition(UUID slateId, SlateStatus from, SlateStatus to, String trigger) {
    }

    /** Runs inside the IPO -> OPEN_MARKET transaction, before the status flips. */
    default void onIpoClosing(UUID slateId) {
    }

    /** Runs after the transaction that moved the slate to CLOSED has committed. */
    default void onSlateClosed(UUID slateId) {
    }

    /** Runs inside the transaction that voids the slate (IPO refunds), and again after commit for order cleanup. */
    default void onSlateVoiding(UUID slateId) {
    }

    default void onSlateVoided(UUID slateId) {
    }
}
