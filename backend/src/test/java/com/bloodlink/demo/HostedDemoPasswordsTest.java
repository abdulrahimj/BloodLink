package com.bloodlink.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The hosted demo's passwords: refused when unsafe, written to the demo accounts only when set. No database needed. */
class HostedDemoPasswordsTest {

    private static final String SHARED = "Shared-demo-pass-1";
    private static final String ADMIN = "Admin-demo-pass-2";

    // ---------- the decision ----------

    @Test
    void theLocalDemoWithNothingSetKeepsThePublishedPassword() {
        assertThat(new HostedDemoPasswordSettings("", null).problems(true)).isEmpty();
    }

    @Test
    void aNonLocalProfileWithNothingSetIsRefused() {
        assertThat(new HostedDemoPasswordSettings(null, " ").problems(false))
            .singleElement().asString()
            .contains("BLOODLINK_DEMO_PASSWORD").contains("BLOODLINK_DEMO_ADMIN_PASSWORD");
    }

    @Test
    void oneSetWithoutTheOtherIsRefusedEvenLocally() {
        assertThat(new HostedDemoPasswordSettings(SHARED, "").problems(true))
            .containsExactly("BLOODLINK_DEMO_ADMIN_PASSWORD is not set");
    }

    @Test
    void thePasswordRuleApplies() {
        assertThat(new HostedDemoPasswordSettings("short", ADMIN).problems(false)).containsExactly(
            "BLOODLINK_DEMO_PASSWORD is too weak: use at least 8 characters, and add a capital letter, a number and a "
                + "special character");
        assertThat(new HostedDemoPasswordSettings(SHARED, "admin-demo-pass").problems(true))
            .containsExactly("BLOODLINK_DEMO_ADMIN_PASSWORD is too weak: add a capital letter and a number");
    }

    @Test
    void thePublishedPasswordIsRefusedForEither() {
        assertThat(new HostedDemoPasswordSettings(SHARED, "BloodLink@2026").problems(false))
            .singleElement().asString().contains("BLOODLINK_DEMO_ADMIN_PASSWORD is the published demo password");
    }

    @Test
    void theTwoMustDiffer() {
        assertThat(new HostedDemoPasswordSettings(SHARED, SHARED).problems(false))
            .singleElement().asString().contains("must differ");
    }

    @Test
    void twoGoodPasswordsPass() {
        assertThat(new HostedDemoPasswordSettings(SHARED, ADMIN).problems(false)).isEmpty();
    }

    @Test
    void neitherPasswordAppearsInToString() {
        assertThat(new HostedDemoPasswordSettings(SHARED, ADMIN).toString()).doesNotContain(SHARED).doesNotContain(ADMIN);
    }

    // ---------- the callback ----------

    private static HostedDemoPasswords callback(String password, String adminPassword, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new HostedDemoPasswords(environment, password, adminPassword);
    }

    @Test
    void demoWithProdAndNoPasswordsStopsBeforeMigrating() {
        HostedDemoPasswords callback = callback("", "", "demo", "prod");

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, mock(Context.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BLOODLINK_DEMO_PASSWORD")
            .hasMessageContaining("DEPLOYMENT.md");
    }

    @Test
    void theLocalDemoWithNothingSetWritesNothing() throws Exception {
        Connection connection = mock(Connection.class);
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        HostedDemoPasswords callback = callback("", "", "demo");

        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE, context);

        verify(connection, never()).prepareStatement(anyString());
    }

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String SELECT_OTHERS = "select password_hash from users where email like ? and role <> 'ADMIN' "
        + "group by password_hash order by count(*) desc limit 1";
    private static final String SELECT_ADMINS = "select password_hash from users where email like ? and role = 'ADMIN' "
        + "group by password_hash order by count(*) desc limit 1";
    private static final String UPDATE = "update users set password_hash = ?, password_changed_at = ? where email like ? ";

    /** Runs the callback against a mock connection whose most common hashes are these; returns the statements by SQL. */
    private static Map<String, PreparedStatement> runWithCurrentHashes(String othersHash, String adminsHash)
            throws Exception {
        Connection connection = mock(Connection.class);
        Map<String, PreparedStatement> statements = new LinkedHashMap<>();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatement statement = mock(PreparedStatement.class);
            if (sql.startsWith("select")) {
                ResultSet rows = mock(ResultSet.class);
                when(rows.next()).thenReturn(true);
                when(rows.getString(1)).thenReturn(sql.equals(SELECT_ADMINS) ? adminsHash : othersHash);
                when(statement.executeQuery()).thenReturn(rows);
            }
            statements.put(sql, statement);
            return statement;
        });
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);

        callback(SHARED, ADMIN, "demo", "prod").handle(Event.AFTER_MIGRATE, context);
        return statements;
    }

    @Test
    void onAFirstStartOrANewSecretEveryDemoAccountGetsItsPasswordAndIsStamped() throws Exception {
        // The seed's own hash, of the published password: neither secret matches it.
        String seedHash = ENCODER.encode("BloodLink@2026");

        Map<String, PreparedStatement> statements = runWithCurrentHashes(seedHash, seedHash);

        assertThat(statements.keySet()).containsExactly(
            SELECT_OTHERS, UPDATE + "and role <> 'ADMIN'", SELECT_ADMINS, UPDATE + "and role = 'ADMIN'");
        PreparedStatement others = statements.get(UPDATE + "and role <> 'ADMIN'");
        PreparedStatement admins = statements.get(UPDATE + "and role = 'ADMIN'");
        assertThat(ENCODER.matches(SHARED, hashWrittenBy(others))).isTrue();
        assertThat(ENCODER.matches(ADMIN, hashWrittenBy(admins))).isTrue();
        // Stamped (Business Rule 32), so a visitor signed in with the old password is signed out.
        verify(others).setTimestamp(org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.any());
        verify(others).setString(3, "%@blooddemo.example.com");
        verify(admins).setString(3, "%@blooddemo.example.com");
    }

    @Test
    void onARestartWithTheSameSecretOnlyAccountsAVisitorChangedAreReset() throws Exception {
        String othersHash = ENCODER.encode(SHARED);
        String adminsHash = ENCODER.encode(ADMIN);

        Map<String, PreparedStatement> statements = runWithCurrentHashes(othersHash, adminsHash);

        // Everyone already on the secret keeps their hash, time and sign-in; the rest get the same hash back.
        String others = UPDATE + "and role <> 'ADMIN' and password_hash <> ?";
        String admins = UPDATE + "and role = 'ADMIN' and password_hash <> ?";
        assertThat(statements.keySet()).containsExactly(SELECT_OTHERS, others, SELECT_ADMINS, admins);
        verify(statements.get(others)).setString(1, othersHash);
        verify(statements.get(others)).setString(4, othersHash);
        verify(statements.get(admins)).setString(1, adminsHash);
        verify(statements.get(admins)).setString(4, adminsHash);
    }

    private static String hashWrittenBy(PreparedStatement statement) throws Exception {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(org.mockito.ArgumentMatchers.eq(1), hash.capture());
        return hash.getValue();
    }
}
