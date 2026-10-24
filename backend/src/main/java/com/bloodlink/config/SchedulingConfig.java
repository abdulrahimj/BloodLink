package com.bloodlink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled methods; the only one is the daily batch expiry job (Business Rule 26),
 * which bloodlink.expiry-notifications.enabled=false removes (the integration tests do).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
