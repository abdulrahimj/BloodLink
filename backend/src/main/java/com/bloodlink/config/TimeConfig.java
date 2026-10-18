package com.bloodlink.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock, in UTC (CLAUDE.md: all timestamps UTC; Sierra Leone is
 * UTC+0 all year, so UTC dates are also local dates there).
 *
 * Injected rather than calling {@code LocalDate.now()} directly so that time can be
 * fixed in tests — the expiry-day boundary (usable at 23:59:59, expired at
 * 00:00:00 UTC) cannot be tested any other way. Used only by the batch-stock code
 * ({@code StockPolicy}, and {@code BatchWriteOffService} for the moment a write-off
 * happens) for now; the existing {@code now()} calls elsewhere are deliberately left
 * as they are.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
