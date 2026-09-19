package com.wbank.platform.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfiguration {

    /**
     * Time is an input to the financial system, not an ambient global. Injecting a
     * {@link Clock} keeps booking timestamps deterministic under test and makes
     * back-dated/value-dated processing a design decision rather than a surprise.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
