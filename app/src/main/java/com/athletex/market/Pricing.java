package com.athletex.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Published settlement formula from docs/fair-value-model.md:
 * settlement = clamp(fantasy_points x POINT_VALUE, FLOOR, CAP).
 */
public final class Pricing {

    public static final long POINT_VALUE_CENTS = 25;
    public static final long FLOOR_CENTS = 1_00;
    public static final long CAP_CENTS = 100_00;

    private Pricing() {
    }

    public static long settlementValueCents(BigDecimal fantasyPoints) {
        long raw = fantasyPoints.multiply(BigDecimal.valueOf(POINT_VALUE_CENTS))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return Math.max(FLOOR_CENTS, Math.min(CAP_CENTS, raw));
    }

    /** IPO reserve price: the projection run through the settlement formula. */
    public static long reservePriceCents(BigDecimal projectedFantasyPoints) {
        return settlementValueCents(projectedFantasyPoints);
    }
}
