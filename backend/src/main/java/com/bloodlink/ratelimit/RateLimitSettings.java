package com.bloodlink.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every number in Business Rule 34. The defaults live here too, because a local application.properties (gitignored)
 * may predate these settings; the BLOODLINK_RATE_LIMIT_ variables are documented in backend/.env.example.
 */
@Component
class RateLimitSettings {

    final int phoneMaxFailures;
    final int phoneWindowMinutes;
    final int phoneLockMinutes;
    final int addressMaxFailures;
    final int addressWindowMinutes;
    final int addressWaitMinutes;
    final int passwordResetMaxRequests;
    final int passwordResetWindowMinutes;
    final int registrationMaxAccounts;
    final int registrationWindowMinutes;
    final int maxEntries;

    RateLimitSettings(
        @Value("${bloodlink.rate-limit.sign-in.phone.max-failures:${BLOODLINK_RATE_LIMIT_PHONE_MAX_FAILURES:3}}")
        int phoneMaxFailures,
        @Value("${bloodlink.rate-limit.sign-in.phone.window-minutes:${BLOODLINK_RATE_LIMIT_PHONE_WINDOW_MINUTES:15}}")
        int phoneWindowMinutes,
        @Value("${bloodlink.rate-limit.sign-in.phone.lock-minutes:${BLOODLINK_RATE_LIMIT_PHONE_LOCK_MINUTES:15}}")
        int phoneLockMinutes,
        @Value("${bloodlink.rate-limit.sign-in.address.max-failures:${BLOODLINK_RATE_LIMIT_ADDRESS_MAX_FAILURES:30}}")
        int addressMaxFailures,
        @Value("${bloodlink.rate-limit.sign-in.address.window-minutes:${BLOODLINK_RATE_LIMIT_ADDRESS_WINDOW_MINUTES:15}}")
        int addressWindowMinutes,
        @Value("${bloodlink.rate-limit.sign-in.address.wait-minutes:${BLOODLINK_RATE_LIMIT_ADDRESS_WAIT_MINUTES:15}}")
        int addressWaitMinutes,
        @Value("${bloodlink.rate-limit.password-reset.max-requests:${BLOODLINK_RATE_LIMIT_PASSWORD_RESET_MAX_REQUESTS:10}}")
        int passwordResetMaxRequests,
        @Value("${bloodlink.rate-limit.password-reset.window-minutes:${BLOODLINK_RATE_LIMIT_PASSWORD_RESET_WINDOW_MINUTES:60}}")
        int passwordResetWindowMinutes,
        @Value("${bloodlink.rate-limit.registration.max-accounts:${BLOODLINK_RATE_LIMIT_REGISTRATION_MAX_ACCOUNTS:10}}")
        int registrationMaxAccounts,
        @Value("${bloodlink.rate-limit.registration.window-minutes:${BLOODLINK_RATE_LIMIT_REGISTRATION_WINDOW_MINUTES:60}}")
        int registrationWindowMinutes,
        @Value("${bloodlink.rate-limit.max-entries:${BLOODLINK_RATE_LIMIT_MAX_ENTRIES:10000}}")
        int maxEntries
    ) {
        this.phoneMaxFailures = phoneMaxFailures;
        this.phoneWindowMinutes = phoneWindowMinutes;
        this.phoneLockMinutes = phoneLockMinutes;
        this.addressMaxFailures = addressMaxFailures;
        this.addressWindowMinutes = addressWindowMinutes;
        this.addressWaitMinutes = addressWaitMinutes;
        this.passwordResetMaxRequests = passwordResetMaxRequests;
        this.passwordResetWindowMinutes = passwordResetWindowMinutes;
        this.registrationMaxAccounts = registrationMaxAccounts;
        this.registrationWindowMinutes = registrationWindowMinutes;
        this.maxEntries = maxEntries;
    }
}
