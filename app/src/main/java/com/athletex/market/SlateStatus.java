package com.athletex.market;

/** Slate lifecycle per docs/slate-lifecycle.md. The linear order drives transition checks. */
public enum SlateStatus {
    SCHEDULED(0),
    IPO(1),
    OPEN_MARKET(2),
    CLOSED(3),
    SETTLED(4),
    VOIDED(-1);

    private final int order;

    SlateStatus(int order) {
        this.order = order;
    }

    /** Position in the happy path; -1 for VOIDED, which is reachable from any non-terminal state. */
    public int order() {
        return order;
    }

    public boolean isTerminal() {
        return this == SETTLED || this == VOIDED;
    }
}
