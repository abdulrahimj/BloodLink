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
import java.util.ArrayList;
import java.util.List;

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

    @Test
    void afterMigratingEachPasswordIsHashedOntoItsOwnDemoAccounts() throws Exception {
        Connection connection = mock(Connection.class);
        List<PreparedStatement> statements = new ArrayList<>();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(connection.prepareStatement(sql.capture())).thenAnswer(invocation -> {
            PreparedStatement statement = mock(PreparedStatement.class);
            statements.add(statement);
            return statement;
        });
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);

        callback(SHARED, ADMIN, "demo", "prod").handle(Event.AFTER_MIGRATE, context);

        assertThat(sql.getAllValues()).containsExactly(
            "update users set password_hash = ? where email like ? and role <> 'ADMIN'",
            "update users set password_hash = ? where email like ? and role = 'ADMIN'");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(encoder.matches(SHARED, hashWrittenBy(statements.get(0)))).isTrue();
        assertThat(encoder.matches(ADMIN, hashWrittenBy(statements.get(1)))).isTrue();
        verify(statements.get(0)).setString(2, "%@blooddemo.example.com");
        verify(statements.get(1)).setString(2, "%@blooddemo.example.com");
    }

    private static String hashWrittenBy(PreparedStatement statement) throws Exception {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(org.mockito.ArgumentMatchers.eq(1), hash.capture());
        return hash.getValue();
    }
}
