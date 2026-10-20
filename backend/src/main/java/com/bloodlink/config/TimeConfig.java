package com.bloodlink.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's UTC clock (Sierra Leone is UTC+0), injected so tests can fix time, e.g. the expiry-day boundary.
 * Used only by batch-stock code for now; other now() calls are deliberately unchanged.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
