package com.bloodlink.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloodlink.web.error.RateLimitedException;

/**
 * The four limits of Business Rule 34, held in server memory: sign-in per phone and per address, "Forgot password?"
 * and registration per address. Nothing here reads the database, so a refusal is the same for any number.
 */
@Component
public class AuthRateLimits {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimits.class);

    private final SlidingWindowLimit signInByPhone;
    private final SlidingWindowLimit signInByAddress;
    private final SlidingWindowLimit passwordResetByAddress;
    private final SlidingWindowLimit registrationByAddress;
    private final int phoneMaxFailures;
    private final SignInLockAudit lockAudit;

    AuthRateLimits(RateLimitSettings settings, Clock clock, SignInLockAudit lockAudit) {
        int entries = settings.maxEntries;
        this.signInByPhone = new SlidingWindowLimit(settings.phoneMaxFailures,
            Duration.ofMinutes(settings.phoneWindowMinutes), Duration.ofMinutes(settings.phoneLockMinutes), entries, clock);
        this.signInByAddress = new SlidingWindowLimit(settings.addressMaxFailures,
            Duration.ofMinutes(settings.addressWindowMinutes), Duration.ofMinutes(settings.addressWaitMinutes), entries, clock);
        this.passwordResetByAddress = new SlidingWindowLimit(settings.passwordResetMaxRequests,
            Duration.ofMinutes(settings.passwordResetWindowMinutes), Duration.ZERO, entries, clock);
        this.registrationByAddress = new SlidingWindowLimit(settings.registrationMaxAccounts,
            Duration.ofMinutes(settings.registrationWindowMinutes), Duration.ZERO, entries, clock);
        this.phoneMaxFailures = settings.phoneMaxFailures;
        this.lockAudit = lockAudit;
    }

    /**
     * Claims a sign-in attempt, or throws the 429. The address goes first, so a blocked address adds no phone entry,
     * and no password has been read yet, so even the correct one is refused.
     */
    public SignInAttempt beginSignIn(String normalisedPhone, String address) {
        refuseIfWaiting(signInByAddress.reserve(address));
        long phoneWait = signInByPhone.reserve(normalisedPhone);
        if (phoneWait > 0) {
            signInByAddress.cancel(address);
            refuseIfWaiting(phoneWait);
        }
        return new SignInAttempt(normalisedPhone, address);
    }

    /** Counts one "Forgot password?" request from this address, or throws the 429. */
    public void countPasswordResetRequest(String address) {
        refuseIfWaiting(passwordResetByAddress.reserve(address));
        passwordResetByAddress.commit(address);
    }

    /** Claims a place for a new account from this address, or throws the 429. Only a created account counts. */
    public RegistrationAttempt beginRegistration(String address) {
        refuseIfWaiting(registrationByAddress.reserve(address));
        return new RegistrationAttempt(address);
    }

    /** An administrator reset this number's password (Business Rule 31): its lock and failed count are forgotten. */
    public void clearSignIn(String normalisedPhone) {
        signInByPhone.clear(normalisedPhone);
    }

    private void refuseIfWaiting(long waitMillis) {
        if (waitMillis > 0) {
            throw new RateLimitedException(waitMillis);
        }
    }

    /** One sign-in in progress. Closing it without an outcome (a suspended account, a server fault) counts nothing. */
    public final class SignInAttempt implements AutoCloseable {

        private final String phone;
        private final String address;
        private boolean settled;

        private SignInAttempt(String phone, String address) {
            this.phone = phone;
            this.address = address;
        }

        /** The password was right: this number's count starts again. The address count stays, or anyone could clear it. */
        public void succeeded() {
            settled = true;
            signInByAddress.cancel(address);
            signInByPhone.clear(phone);
        }

        /** The phone and password did not match. The attempt that locks the number writes the one audit entry. */
        public void failed() {
            settled = true;
            signInByAddress.commit(address);
            if (signInByPhone.commit(phone)) {
                recordLock();
            }
        }

        @Override
        public void close() {
            if (!settled) {
                signInByAddress.cancel(address);
                signInByPhone.cancel(phone);
            }
        }

        /** The lock already stands, so a failed entry is only logged, by type: a database error could quote the row. */
        private void recordLock() {
            try {
                lockAudit.record(phone, phoneMaxFailures);
            } catch (RuntimeException ex) {
                log.warn("A sign-in lock could not be written to the audit log ({}).", ex.getClass().getSimpleName());
            }
        }
    }

    /** One registration in progress. Closing it without succeeded() gives the place back. */
    public final class RegistrationAttempt implements AutoCloseable {

        private final String address;
        private boolean settled;

        private RegistrationAttempt(String address) {
            this.address = address;
        }

        public void succeeded() {
            settled = true;
            registrationByAddress.commit(address);
        }

        @Override
        public void close() {
            if (!settled) {
                registrationByAddress.cancel(address);
            }
        }
    }
}
