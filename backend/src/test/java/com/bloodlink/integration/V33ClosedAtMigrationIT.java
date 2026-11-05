package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V33's closed_at back-fill proven row by row (Business Rule 36): stops at V32, writes requests in every state, then
 * runs V33 for real, on its own bloodlink_v33_migration_test (created and dropped per test), like V19CutoverMigrationIT.
 */
class V33ClosedAtMigrationIT {

    private static final String DATABASE = "bloodlink_v33_migration_test";
    private static final Instant CREATED = Instant.parse("2026-06-01T08:00:00Z");

    private Connection db;

    @BeforeEach
    void createDatabase() throws Exception {
        runOnServer("drop database if exists " + DATABASE + " with (force)");
        runOnServer("create database " + DATABASE);
        db = DriverManager.getConnection(databaseUrl(), username(), password());
    }

    @AfterEach
    void dropDatabase() throws Exception {
        if (db != null) {
            db.close();
        }
        runOnServer("drop database if exists " + DATABASE + " with (force)");
    }

    @Test
    void everyFinishedRequestGetsItsBestKnownClosingTimeAndOpenOnesNone() throws Exception {
        migrateTo("32");
        UUID hospital = id("select id from hospitals order by name limit 1");
        UUID bankA = id("select id from blood_banks order by name limit 1");
        UUID bankB = id("select id from blood_banks order by name desc limit 1");
        UUID clerk = id("""
            insert into users (full_name, email, password_hash, role, phone, hospital_id)
            values ('Migration Clerk', 'v33.clerk@example.com', 'x', 'HOSPITAL_STAFF', '+23230000001', '%s')
            returning id""".formatted(hospital));

        Instant firstGift = CREATED.plusSeconds(3_600);
        Instant lastGift = CREATED.plusSeconds(7_200);
        UUID fulfilled = request(hospital, "FULFILLED");
        contribution(fulfilled, bankA, 1, firstGift);
        contribution(fulfilled, bankB, 1, lastGift);

        UUID fulfilledWithoutRows = request(hospital, "FULFILLED");

        Instant lastDecline = CREATED.plusSeconds(9_000);
        UUID rejected = request(hospital, "REJECTED");
        decline(rejected, bankA, CREATED.plusSeconds(600));
        decline(rejected, bankB, lastDecline);

        // Rejected before V9 recorded declines: no evidence but when it was raised.
        UUID rejectedWithoutRows = request(hospital, "REJECTED");

        Instant cancelledAt = CREATED.plusSeconds(5_000);
        UUID cancelled = id("""
            insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, created_at,
                                        cancellation_reason, cancelled_by, cancelled_at)
            values ('%s', 'O+', 2, 'URGENT', 'CANCELLED', '%s', 'NO_LONGER_NEEDED', '%s', '%s') returning id
            """.formatted(hospital, Timestamp.from(CREATED), clerk, Timestamp.from(cancelledAt)));

        UUID pending = request(hospital, "PENDING");
        UUID partly = request(hospital, "PARTIALLY_FULFILLED");
        contribution(partly, bankA, 1, firstGift);

        migrateTo("33");

        assertThat(closedAt(fulfilled)).isEqualTo(lastGift);
        assertThat(closedAt(fulfilledWithoutRows)).isEqualTo(CREATED);
        assertThat(closedAt(rejected)).isEqualTo(lastDecline);
        assertThat(closedAt(rejectedWithoutRows)).isEqualTo(CREATED);
        assertThat(closedAt(cancelled)).isEqualTo(cancelledAt);
        assertThat(closedAt(pending)).isNull();
        assertThat(closedAt(partly)).isNull();

        // From now on an open request can never carry a closing time.
        assertThatThrownBy(() -> update(
            "update blood_requests set closed_at = now() where id = '%s'".formatted(pending)))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("blood_requests_closed_at_only_when_finished");
    }

    // ---------- helpers ----------

    private void migrateTo(String version) {
        Flyway.configure()
            .dataSource(databaseUrl(), username(), password())
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }

    /** A request as an older version wrote it, with plain SQL (no ON CONFLICT: a clash must fail loudly). */
    private UUID request(UUID hospital, String status) throws Exception {
        return id("""
            insert into blood_requests (hospital_id, blood_type, units_requested, urgency_level, status, created_at)
            values ('%s', 'O+', 2, 'URGENT', '%s', '%s') returning id
            """.formatted(hospital, status, Timestamp.from(CREATED)));
    }

    private void contribution(UUID request, UUID bank, int units, Instant at) throws Exception {
        update("""
            insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed, contributed_at)
            values ('%s', '%s', %d, '%s')""".formatted(request, bank, units, Timestamp.from(at)));
    }

    private void decline(UUID request, UUID bank, Instant at) throws Exception {
        update("""
            insert into blood_request_declines (blood_request_id, blood_bank_id, declined_at)
            values ('%s', '%s', '%s')""".formatted(request, bank, Timestamp.from(at)));
    }

    private Instant closedAt(UUID request) throws Exception {
        try (PreparedStatement query = db.prepareStatement("select closed_at from blood_requests where id = ?")) {
            query.setObject(1, request);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                Timestamp value = rs.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    private UUID id(String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private void update(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    // ---------- the throwaway database, on the test profile's server ----------

    private static void runOnServer(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(serverUrl(), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String databaseUrl() {
        return serverUrl().replace("/postgres", "/" + DATABASE);
    }

    private static String serverUrl() {
        return TestDatabaseLogin.serverUrl();
    }

    private static String username() {
        return TestDatabaseLogin.username();
    }

    private static String password() {
        return TestDatabaseLogin.password();
    }
}
