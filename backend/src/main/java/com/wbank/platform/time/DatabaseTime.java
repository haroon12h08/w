package com.wbank.platform.time;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instants at the precision PostgreSQL stores ({@code timestamptz}: microseconds).
 *
 * <p>A Java clock can carry nanoseconds; PostgreSQL rounds them away on write. Any instant
 * that is both stored and used in a computation (a knowledge cutoff, an evaluation time)
 * must be normalized first, or the stored value and the value the computation used are
 * different instants and the result cannot be reproduced from what was stored. This was
 * found on a live database in phase 8 (docs/outcome-research.md), where tests with a
 * whole-second clock could not see it.
 *
 * <p>Truncation, not rounding: a normalized instant is never later than the original, so a
 * knowledge cutoff is never moved into the future.
 */
public final class DatabaseTime {

    private DatabaseTime() {}

    public static Instant normalize(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** The clock's current instant, normalized. Read once per operation. */
    public static Instant now(Clock clock) {
        return normalize(clock.instant());
    }
}
