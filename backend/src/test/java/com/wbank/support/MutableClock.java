package com.wbank.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock tests can move. Lending is about time (due dates, accrual, days past due), so
 * lending tests control "today" explicitly instead of waiting for it.
 */
public class MutableClock extends Clock {

    public static final Instant EPOCH = Instant.parse("2026-01-15T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(EPOCH);

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void reset() {
        now.set(EPOCH);
    }

    public void advanceDays(long days) {
        now.updateAndGet(i -> i.plus(Duration.ofDays(days)));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    /** Import into a test to replace the system clock in that test's application context. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
