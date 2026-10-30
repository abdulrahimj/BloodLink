package com.bloodlink.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.bloodlink.security.JwtSecretPolicy;

/**
 * On a hosted demo (demo,prod) replaces the seed's published password with two secrets, as a Flyway callback, so before
 * any sign-in; refuses to start without them. The plain local demo, with neither set, keeps BloodLink@2026.
 */
@Component
@Profile("demo")
class HostedDemoPasswords implements Callback {

    private static final Logger log = LoggerFactory.getLogger(HostedDemoPasswords.class);

    /** Every account the demo seed creates, and only those, has an address at this reserved domain. */
    static final String DEMO_EMAIL_PATTERN = "%@blooddemo.example.com";

    private final Environment environment;
    private final HostedDemoPasswordSettings settings;

    // Each falls back to its environment variable, like the bootstrap-admin settings.
    HostedDemoPasswords(
        Environment environment,
        @Value("${bloodlink.demo.password:${BLOODLINK_DEMO_PASSWORD:}}") String password,
        @Value("${bloodlink.demo.admin-password:${BLOODLINK_DEMO_ADMIN_PASSWORD:}}") String adminPassword
    ) {
        this.environment = environment;
        this.settings = new HostedDemoPasswordSettings(password, adminPassword);
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE || event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_MIGRATE) {
            refuseUnsafeSettings();
        } else if (!settings.noneSet()) {
            applyPasswords(context.getConnection());
        }
    }

    @Override
    public String getCallbackName() {
        return "HostedDemoPasswords";
    }

    /** Stops the start before any demo row is written, naming the variables but never a value. */
    private void refuseUnsafeSettings() {
        List<String> problems = settings.problems(JwtSecretPolicy.isLocalOnly(environment.getActiveProfiles()));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The hosted demo cannot start: " + String.join("; ", problems)
                + ". See DEPLOYMENT.md, \"Demo passwords\".");
        }
    }

    /** On every start (undoing any visitor's change): one hash per password, one update each, since 850 hashes would
     *  take minutes on 0.1 CPU. Not the PasswordEncoder bean, which depends (via security) on Flyway itself. */
    private void applyPasswords(Connection connection) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        try {
            int others = update(connection, encoder.encode(settings.password()), "role <> 'ADMIN'");
            int admins = update(connection, encoder.encode(settings.adminPassword()), "role = 'ADMIN'");
            log.info("Demo passwords set from {} ({} accounts) and {} ({} administrator accounts).",
                HostedDemoPasswordSettings.PASSWORD_VARIABLE, others,
                HostedDemoPasswordSettings.ADMIN_PASSWORD_VARIABLE, admins);
        } catch (SQLException ex) {
            // Only the type: the statement's text would carry a password hash.
            throw new IllegalStateException("Setting the demo passwords failed (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private static int update(Connection connection, String hash, String roleCondition) throws SQLException {
        String sql = "update users set password_hash = ? where email like ? and " + roleCondition;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash);
            statement.setString(2, DEMO_EMAIL_PATTERN);
            return statement.executeUpdate();
        }
    }
}
