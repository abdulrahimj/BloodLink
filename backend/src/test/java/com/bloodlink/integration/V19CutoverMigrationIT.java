package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * V19, the cutover to batch-level stock, proven row by row on a database of its own.
 *
 * <h2>Why this test has its own database</h2>
 * It has to stop the schema at V17, write legacy blood_inventory rows the way the old
 * application did, and then run V18 and V19 for real: Flyway migrations commit, so
 * this cannot be a rolled-back test on the shared bloodlink_test fixture. Following
 * CLAUDE.md's convention for exactly that case (as PartialFulfilmentRaceIT does), it
 * creates {@code bloodlink_migration_test} before each test, builds it from scratch
 * with Flyway, and drops it afterwards. No Spring context is started at all: the
 * migration is the thing under test, so it is driven through Flyway directly.
 *
 * <pre>{@code .\mvnw.cmd test -Dtest=V19CutoverMigrationIT}</pre>
 */
@ExtendWith(OutputCaptureExtension.class)
class V19CutoverMigrationIT {

    private static final String DATABASE = "bloodlink_migration_test";
    /** V19 decides "already expired" with the UTC date, as StockPolicy does. */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

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

    // ---------- the conversion ----------

    @Test
    void everyLegacyRowWithStockBecomesOneMatchingBatchAndZeroRowsBecomeNone(CapturedOutput output) throws Exception {
        migrateTo("17");
        UUID makeni = bankId("Makeni Regional Blood Bank");
        UUID holySpirit = bankId("Holy Spirit Blood Bank");
        UUID third = insertBank("Migration Test Blood Bank");

        Instant updated = Instant.parse("2026-09-20T10:15:30Z");
        Legacy normal = legacy(makeni, "O+", 120, TODAY.plusDays(30), updated);
        Legacy staleExpired = legacy(makeni, "B+", 2, TODAY.minusDays(4), updated.minus(12, ChronoUnit.DAYS));
        Legacy zero = legacy(makeni, "O-", 0, TODAY.plusDays(55), updated);
        Legacy sameDateA = legacy(holySpirit, "A+", 10, TODAY.plusDays(55), updated);
        Legacy sameDateB = legacy(holySpirit, "AB+", 40, TODAY.plusDays(55), updated);
        Legacy expiresToday = legacy(holySpirit, "O+", 7, TODAY, updated);
        Legacy zeroAndStale = legacy(third, "B-", 0, TODAY.minusDays(10), updated);
        List<Legacy> all = List.of(normal, staleExpired, zero, sameDateA, sameDateB, expiresToday, zeroAndStale);

        migrateTo("19");

        assertThat(schemaVersion()).isEqualTo("19");

        // Row by row: same id, bank, group and date; received = remaining = old units;
        // received_at and last_updated = the old last_updated; MIGRATED; no donation.
        for (Legacy row : List.of(normal, staleExpired, sameDateA, sameDateB, expiresToday)) {
            Batch batch = batch(row.id());
            assertThat(batch).as("batch for legacy row %s %s", row.type(), row.units()).isNotNull();
            assertThat(batch.bankId()).isEqualTo(row.bankId());
            assertThat(batch.type()).isEqualTo(row.type());
            assertThat(batch.received()).isEqualTo(row.units());
            assertThat(batch.remaining()).isEqualTo(row.units());
            assertThat(batch.expiry()).isEqualTo(row.expiry());
            assertThat(batch.receivedAt()).isEqualTo(row.lastUpdated());
            assertThat(batch.lastUpdated()).isEqualTo(row.lastUpdated());
            assertThat(batch.source()).isEqualTo("MIGRATED");
            assertThat(batch.donationId()).isNull();
        }

        // Only the batch already past expiry is stamped as noticed, so no future
        // expiry alert can fire for it. One expiring TODAY is still usable: not noticed.
        assertThat(batch(staleExpired.id()).noticed()).isTrue();
        for (Legacy row : List.of(normal, sameDateA, sameDateB, expiresToday)) {
            assertThat(batch(row.id()).noticed()).as("%s noticed", row.type()).isFalse();
        }

        // Rows with 0 units become no batch at all.
        assertThat(batch(zero.id())).isNull();
        assertThat(batch(zeroAndStale.id())).isNull();
        assertThat(count("select count(*) from blood_inventory_batches")).isEqualTo(5);

        // Per bank and group, the batches hold exactly what the rows held.
        for (Legacy row : all) {
            long batchUnits = count("select coalesce(sum(units_remaining), 0) from blood_inventory_batches "
                + "where blood_bank_id = '" + row.bankId() + "' and blood_type = '" + row.type() + "'");
            assertThat(batchUnits).as("%s %s", row.bankId(), row.type()).isEqualTo(row.units());
        }

        // The stock identity holds for every converted batch.
        assertThat(count("""
            select count(*) from blood_inventory_batches b
             where b.units_received <> b.units_remaining
                   + coalesce((select sum(a.units_allocated) from contribution_allocations a where a.batch_id = b.id), 0)
                   + coalesce((select sum(w.units) from batch_write_offs w where w.batch_id = b.id), 0)
            """)).isZero();

        // The migration output names every skipped row and the already-expired batch.
        String log = output.getOut() + output.getErr();
        assertThat(log)
            .contains("V19: skipped Makeni Regional Blood Bank O- (row " + zero.id() + ")")
            .contains("V19: skipped Migration Test Blood Bank B- (row " + zeroAndStale.id() + ")")
            .contains("V19: Makeni Regional Blood Bank B+ converted to an ALREADY EXPIRED batch: 2 unit(s)")
            .contains("V19: converted 5 row(s) into MIGRATED batches; skipped 2 row(s) with 0 units");
    }

    @Test
    void theLegacyTableIsRenamedWithItsRowsUntouchedAndNoOldNameIsLeftToClashWith() throws Exception {
        migrateTo("17");
        UUID makeni = bankId("Makeni Regional Blood Bank");
        legacy(makeni, "O+", 12, TODAY.plusDays(30), Instant.parse("2026-09-01T00:00:00Z"));
        legacy(makeni, "A-", 0, TODAY.plusDays(30), Instant.parse("2026-09-01T00:00:00Z"));
        String before = fingerprint("blood_inventory");

        migrateTo("19");

        assertThat(exists("select 1 from pg_class where relname = 'blood_inventory'")).isFalse();
        assertThat(exists("select 1 from pg_class where relname = 'blood_inventory_pre_batches'")).isTrue();
        // Kept exactly as it was, zero row included, as a record of what was converted.
        assertThat(fingerprint("blood_inventory_pre_batches")).isEqualTo(before);

        // Every index-backed name is renamed, so nothing new can ever collide with it.
        for (String old : List.of("blood_inventory_pkey", "uq_blood_inventory_bank_type", "idx_blood_inventory_bank")) {
            assertThat(exists("select 1 from pg_class where relname = '" + old + "'")).as(old).isFalse();
        }
        for (String renamed : List.of("blood_inventory_pre_batches_pkey", "uq_blood_inventory_pre_batches_bank_type",
                "idx_blood_inventory_pre_batches_bank")) {
            assertThat(exists("select 1 from pg_class where relname = '" + renamed + "'")).as(renamed).isTrue();
        }
        // And every constraint on it carries the new table name, whichever PostgreSQL
        // version named which constraints.
        assertThat(count("""
            select count(*) from pg_constraint
             where conrelid = 'blood_inventory_pre_batches'::regclass
               and conname not like 'blood\\_inventory\\_pre\\_batches\\_%'
               and conname <> 'uq_blood_inventory_pre_batches_bank_type'
            """)).isZero();
        assertThat(string("select obj_description('blood_inventory_pre_batches'::regclass, 'pg_class')"))
            .contains("dropped by V20");
    }

    @Test
    void anEmptyLegacyTableConvertsToNothingAndStillRetiresTheTable() throws Exception {
        migrateTo("19");

        assertThat(schemaVersion()).isEqualTo("19");
        assertThat(count("select count(*) from blood_inventory_batches")).isZero();
        assertThat(exists("select 1 from pg_class where relname = 'blood_inventory_pre_batches'")).isTrue();
    }

    // ---------- the pre-flight refusals ----------

    @Test
    void negativeUnitsAreRefusedNothingIsChangedAndTheMigrationRunsOnceTheRowIsCorrected() throws Exception {
        migrateTo("17");
        UUID makeni = bankId("Makeni Regional Blood Bank");
        Legacy negative = legacy(makeni, "AB-", -3, TODAY.plusDays(30), Instant.parse("2026-09-01T00:00:00Z"));
        legacy(makeni, "O+", 9, TODAY.plusDays(30), Instant.parse("2026-09-01T00:00:00Z"));

        assertThatThrownBy(() -> migrateTo("19"))
            .hasStackTraceContaining("negative number of units")
            .hasStackTraceContaining("Makeni Regional Blood Bank AB-");

        // V18 is additive and committed on its own; V19 rolled back entirely.
        assertThat(schemaVersion()).isEqualTo("18");
        assertThat(exists("select 1 from pg_class where relname = 'blood_inventory'")).isTrue();
        assertThat(count("select count(*) from blood_inventory_batches")).isZero();

        // Once a person corrects the row, simply running again completes it: no
        // repair step is needed, because nothing of the failed attempt was recorded.
        update("update blood_inventory set units_available = 3 where id = '" + negative.id() + "'");
        migrateTo("19");

        assertThat(schemaVersion()).isEqualTo("19");
        assertThat(count("select count(*) from blood_inventory_batches")).isEqualTo(2);
    }

    @Test
    void batchesThatAlreadyExistAreRefusedRatherThanCountedTwice() throws Exception {
        migrateTo("18");
        UUID makeni = bankId("Makeni Regional Blood Bank");
        legacy(makeni, "O+", 9, TODAY.plusDays(30), Instant.parse("2026-09-01T00:00:00Z"));
        update("insert into blood_inventory_batches (blood_bank_id, blood_type, units_received, units_remaining, "
            + "expiry_date, source) values ('" + makeni + "', 'O+', 9, 9, '" + TODAY.plusDays(30) + "', 'MANUAL')");

        assertThatThrownBy(() -> migrateTo("19")).hasStackTraceContaining("already holds 1 row(s)");

        assertThat(schemaVersion()).isEqualTo("18");
        assertThat(count("select count(*) from blood_inventory_batches")).isEqualTo(1);
    }

    // ---------- helpers ----------

    private record Legacy(UUID id, UUID bankId, String type, int units, LocalDate expiry, Instant lastUpdated) {}

    private record Batch(UUID bankId, String type, int received, int remaining, LocalDate expiry,
                         Instant receivedAt, Instant lastUpdated, String source, UUID donationId, boolean noticed) {}

    private void migrateTo(String version) {
        Flyway.configure()
            .dataSource(databaseUrl(), username(), password())
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }

    /** A blood_inventory row exactly as the old application wrote one, with plain SQL
     *  (no ON CONFLICT: a clash must fail loudly). */
    private Legacy legacy(UUID bankId, String type, int units, LocalDate expiry, Instant lastUpdated) throws Exception {
        try (PreparedStatement insert = db.prepareStatement("""
                insert into blood_inventory (blood_bank_id, blood_type, units_available, expiry_date, last_updated)
                values (?, ?, ?, ?, ?) returning id
                """)) {
            insert.setObject(1, bankId);
            insert.setString(2, type);
            insert.setInt(3, units);
            insert.setObject(4, expiry);
            insert.setTimestamp(5, Timestamp.from(lastUpdated));
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return new Legacy(rs.getObject(1, UUID.class), bankId, type, units, expiry, lastUpdated);
            }
        }
    }

    private Batch batch(UUID id) throws Exception {
        try (PreparedStatement query = db.prepareStatement("""
                select blood_bank_id, blood_type, units_received, units_remaining, expiry_date,
                       received_at, last_updated, source, donation_id, expiry_noticed_at is not null
                  from blood_inventory_batches where id = ?
                """)) {
            query.setObject(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Batch(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
                    rs.getObject(5, LocalDate.class), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(),
                    rs.getString(8), rs.getObject(9, UUID.class), rs.getBoolean(10));
            }
        }
    }

    private UUID bankId(String name) throws Exception {
        try (PreparedStatement query = db.prepareStatement("select id from blood_banks where name = ?")) {
            query.setString(1, name);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private UUID insertBank(String name) throws Exception {
        try (PreparedStatement insert = db.prepareStatement(
                "insert into blood_banks (name, location, contact_phone) values (?, 'Makeni', '076 000 000') returning id")) {
            insert.setString(1, name);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** Every row of a table, as text, in a stable order: equal means unchanged. */
    private String fingerprint(String table) throws Exception {
        return string("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '') from " + table + " t");
    }

    private String schemaVersion() throws Exception {
        return string("select max(version::int)::text from flyway_schema_history where success");
    }

    private long count(String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String string(String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean exists(String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        }
    }

    private void update(String sql) throws Exception {
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
        String testUrl = testProperties().getProperty("spring.datasource.url");
        return testUrl.substring(0, testUrl.lastIndexOf('/')) + "/postgres";
    }

    private static String username() {
        return testProperties().getProperty("spring.datasource.username");
    }

    private static String password() {
        return testProperties().getProperty("spring.datasource.password");
    }

    /** The credentials the test profile already declares, so there is no second copy. */
    private static Properties testProperties() {
        Properties properties = new Properties();
        try (InputStream in = V19CutoverMigrationIT.class.getResourceAsStream("/application-test.properties")) {
            properties.load(in);
        } catch (Exception cannotRead) {
            throw new IllegalStateException("application-test.properties is required for this test", cannotRead);
        }
        return properties;
    }
}
