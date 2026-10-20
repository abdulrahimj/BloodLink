package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.BloodRequest;
import com.bloodlink.domain.BloodRequestContribution;
import com.bloodlink.domain.ContributionAllocation;
import com.bloodlink.domain.Donation;
import com.bloodlink.domain.Donor;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BatchSource;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.Gender;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.DonationRepository;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.stock.FefoAllocator;
import com.bloodlink.stock.FefoAllocator.Candidate;

import jakarta.persistence.EntityManager;

/**
 * V18's constraints and triggers exercised directly in SQL, proving they hold in PostgreSQL itself;
 * assertRefused wraps each refused statement in a savepoint. @Transactional, rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BatchStockSchemaIT {

    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000L);
    private static final LocalDate IN_30_DAYS = LocalDate.now().plusDays(30);

    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DonorRepository donorRepository;
    @Autowired private DonationRepository donationRepository;
    @Autowired private BloodRequestRepository bloodRequestRepository;
    @Autowired private BloodRequestContributionRepository contributionRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private ContributionAllocationRepository allocationRepository;
    @Autowired private BatchWriteOffRepository writeOffRepository;

    private JdbcTemplate jdbc;
    private BloodBank bank;
    private BloodBank otherBank;
    private Hospital hospital;
    private User staff;
    private Donation donation;
    private BloodRequest request;

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);

        List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
        bank = banks.get(0);
        otherBank = banks.get(1);
        hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
        staff = user("Batch IT Technician", Role.BLOOD_BANK_STAFF, bank);

        Donor donor = new Donor();
        donor.setUser(user("Batch IT Donor", Role.DONOR, null));
        donor.setBloodType(BloodType.O_NEGATIVE);
        donor.setDateOfBirth(LocalDate.of(1994, 3, 2));
        donor.setGender(Gender.FEMALE);
        donor.setAddress("Makeni");
        donorRepository.save(donor);

        donation = new Donation();
        donation.setDonor(donor);
        donation.setBloodBank(bank);
        donation.setDonationDate(LocalDate.now());
        donation.setUnitsDonated(2);
        donation.setBloodType(BloodType.O_NEGATIVE);
        donationRepository.save(donation);

        request = new BloodRequest();
        request.setHospital(hospital);
        request.setBloodType(BloodType.O_NEGATIVE);
        request.setUnitsRequested(10);
        request.setUrgencyLevel(UrgencyLevel.URGENT);
        request.setStatus(RequestStatus.PENDING);
        bloodRequestRepository.save(request);

        // The JPA rows above must reach the database before plain SQL can see them.
        entityManager.flush();
    }

    // ---------- units: never negative, never more than arrived ----------

    @Test
    void impossibleUnitFiguresAreRefusedOnInsert() {
        assertRefused("chk_batches_units_received_positive",
            insertBatchSql(), bank.getId(), "O-", 0, 0, IN_30_DAYS, "MANUAL", null);
        assertRefused("chk_batches_units_remaining_in_range",
            insertBatchSql(), bank.getId(), "O-", 5, -1, IN_30_DAYS, "MANUAL", null);
        assertRefused("chk_batches_units_remaining_in_range",
            insertBatchSql(), bank.getId(), "O-", 5, 6, IN_30_DAYS, "MANUAL", null);
    }

    @Test
    void unitsRemainingCanFallToZeroButNotBelowAndNeverRiseAboveReceived() {
        UUID batch = insertBatch(5, 5, IN_30_DAYS);

        assertRefused("chk_batches_units_remaining_in_range",
            "update blood_inventory_batches set units_remaining = -1 where id = ?", batch);
        assertRefused("chk_batches_units_remaining_in_range",
            "update blood_inventory_batches set units_remaining = 6 where id = ?", batch);

        assertThat(jdbc.update("update blood_inventory_batches set units_remaining = 0 where id = ?", batch))
            .isEqualTo(1);
    }

    // ---------- identity columns are fixed ----------

    @Test
    void everyIdentityColumnOfABatchIsFixedOnceItExists() {
        UUID batch = insertBatch(5, 5, IN_30_DAYS);
        UUID otherDonation = donation.getId();

        Map<String, String> changes = Map.of(
            "blood_bank_id", "blood_bank_id = '" + otherBank.getId() + "'",
            "blood_type", "blood_type = 'A+'",
            // Raised, so the units CHECK would still pass: it is the trigger that refuses.
            "units_received", "units_received = units_received + 1",
            "expiry_date", "expiry_date = expiry_date + 1",
            "received_at", "received_at = received_at - interval '1 day'",
            "donation_id", "donation_id = '" + otherDonation + "'",
            "source", "source = 'MIGRATED'",
            "id", "id = gen_random_uuid()"
        );
        changes.forEach((column, assignment) -> assertRefused(
            "blood_inventory_batches." + column + " cannot be changed once a batch exists",
            "update blood_inventory_batches set " + assignment + " where id = ?", batch));
    }

    @Test
    void theColumnsThatAreMeantToChangeCanChange() {
        UUID batch = insertBatch(5, 5, IN_30_DAYS);

        int updated = jdbc.update("""
            update blood_inventory_batches
               set units_remaining = 2, last_updated = now(), expiry_noticed_at = now()
             where id = ?
            """, batch);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select units_remaining from blood_inventory_batches where id = ?", Integer.class, batch)).isEqualTo(2);
    }

    // ---------- source, donation and blood group ----------

    @Test
    void aDonationBatchNamesItsDonationAndNoOtherBatchDoes() {
        assertRefused("chk_batches_donation_matches_source",
            insertBatchSql(), bank.getId(), "O-", 1, 1, IN_30_DAYS, "DONATION", null);
        assertRefused("chk_batches_donation_matches_source",
            insertBatchSql(), bank.getId(), "O-", 1, 1, IN_30_DAYS, "MANUAL", donation.getId());
        assertRefused("chk_batches_source",
            insertBatchSql(), bank.getId(), "O-", 1, 1, IN_30_DAYS, "GIFT", null);
        assertRefused("blood_inventory_batches_blood_type_check",
            insertBatchSql(), bank.getId(), "C+", 1, 1, IN_30_DAYS, "MANUAL", null);
    }

    @Test
    void oneDonationMakesAtMostOneBatch() {
        jdbc.update(insertBatchSql(), bank.getId(), "O-", 1, 1, IN_30_DAYS, "DONATION", donation.getId());

        assertRefused("uq_batches_donation",
            insertBatchSql(), bank.getId(), "O-", 1, 1, IN_30_DAYS, "DONATION", donation.getId());

        // Batches with no donation never collide with one another (NULLs are distinct).
        insertBatch(3, 3, IN_30_DAYS);
        insertBatch(3, 3, IN_30_DAYS);
    }

    // ---------- contribution_allocations ----------

    @Test
    void aContributionTakesFromAGivenBatchOnceAndAlwaysAtLeastOneUnit() {
        UUID contribution = insertContribution(4);
        UUID first = insertBatch(5, 5, IN_30_DAYS);
        UUID second = insertBatch(5, 5, IN_30_DAYS.plusDays(1));

        insertAllocation(contribution, first, 3);
        assertRefused("uq_allocation_contribution_batch",
            "insert into contribution_allocations (contribution_id, batch_id, units_allocated) values (?, ?, ?)",
            contribution, first, 1);
        assertRefused("chk_allocations_units_positive",
            "insert into contribution_allocations (contribution_id, batch_id, units_allocated) values (?, ?, ?)",
            contribution, second, 0);

        // The same contribution may draw on a second batch.
        insertAllocation(contribution, second, 1);
    }

    @Test
    void allocationsAreAppendOnly() {
        UUID allocation = insertAllocation(insertContribution(2), insertBatch(5, 5, IN_30_DAYS), 2);

        assertRefused("contribution_allocations is append-only: UPDATE is not permitted",
            "update contribution_allocations set units_allocated = 1 where id = ?", allocation);
        assertRefused("contribution_allocations is append-only: DELETE is not permitted",
            "delete from contribution_allocations where id = ?", allocation);
        assertRefused("contribution_allocations is append-only: TRUNCATE is not permitted",
            "truncate contribution_allocations");
        // A TRUNCATE that reaches the table by CASCADE from contributions is refused too.
        assertRefused("contribution_allocations is append-only: TRUNCATE is not permitted",
            "truncate blood_request_contributions cascade");
    }

    @Test
    void stockThatWasIssuedCannotHaveItsHistoryDeletedFromUnderneathIt() {
        // NO ACTION foreign keys: a batch or contribution that appears in an
        // allocation cannot be deleted, so the record of issued blood cannot vanish.
        UUID contribution = insertContribution(2);
        UUID batch = insertBatch(5, 3, IN_30_DAYS);
        insertAllocation(contribution, batch, 2);

        assertRefused("contribution_allocations", "delete from blood_inventory_batches where id = ?", batch);
        assertRefused("contribution_allocations", "delete from blood_request_contributions where id = ?", contribution);
    }

    // ---------- batch_write_offs ----------

    @Test
    void aWriteOffNeedsPositiveUnitsAKnownReasonAndAProperNote() {
        UUID batch = insertBatch(5, 5, IN_30_DAYS);
        String sql = "insert into batch_write_offs (batch_id, units, reason, note, written_off_by_user_id) values (?, ?, ?, ?, ?)";

        assertRefused("chk_write_offs_units_positive", sql, batch, 0, "EXPIRED", null, staff.getId());
        assertRefused("chk_write_offs_reason", sql, batch, 1, "LOST", null, staff.getId());
        assertRefused("chk_write_offs_note_required_for_other", sql, batch, 1, "OTHER", null, staff.getId());
        assertRefused("chk_write_offs_note_not_blank", sql, batch, 1, "DAMAGED", "   ", staff.getId());
        assertRefused("chk_write_offs_note_length", sql, batch, 1, "DAMAGED", "x".repeat(501), staff.getId());

        // Each of the five agreed reasons is accepted; OTHER with its note.
        for (String reason : List.of("EXPIRED", "DAMAGED", "FAILED_TESTING", "DATA_ENTRY_ERROR")) {
            jdbc.update(sql, batch, 1, reason, null, staff.getId());
        }
        jdbc.update(sql, batch, 1, "OTHER", "Bag mislabelled at the donor session", staff.getId());
    }

    @Test
    void writeOffsAreAppendOnly() {
        UUID writeOff = insertWriteOff(insertBatch(5, 3, IN_30_DAYS), 2, "DAMAGED", null);

        assertRefused("batch_write_offs is append-only: UPDATE is not permitted",
            "update batch_write_offs set units = 1 where id = ?", writeOff);
        assertRefused("batch_write_offs is append-only: DELETE is not permitted",
            "delete from batch_write_offs where id = ?", writeOff);
        assertRefused("batch_write_offs is append-only: TRUNCATE is not permitted",
            "truncate batch_write_offs");
    }

    // ---------- audit_log: STOCK_WRITTEN_OFF and its one target ----------

    @Test
    void aStockWriteOffEntryPointsAtItsBatchAndAtNothingElse() {
        UUID batch = insertBatch(5, 5, IN_30_DAYS);
        String sql = """
            insert into audit_log (actor_user_id, action, target_user_id, target_blood_bank_id,
                                   target_hospital_id, target_batch_id, details)
            values (?, ?, ?, ?, ?, ?, 'Stage 1 schema test')
            """;

        // Accepted: the batch is the one target. The actor is bank staff, not an
        // administrator: write-offs by bank staff appear in the Admin Audit Log.
        assertThat(jdbc.update(sql, staff.getId(), "STOCK_WRITTEN_OFF", null, null, null, batch)).isEqualTo(1);

        // Refused: no target, or the batch plus another target.
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "STOCK_WRITTEN_OFF", null, null, null, null);
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "STOCK_WRITTEN_OFF", staff.getId(), null, null, batch);
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "STOCK_WRITTEN_OFF", null, bank.getId(), null, batch);
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "STOCK_WRITTEN_OFF", null, null, hospital.getId(), batch);

        // The older actions may not carry a batch target either.
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "ACCOUNT_SUSPENDED", staff.getId(), null, null, batch);
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "BLOOD_BANK_CREATED", null, bank.getId(), null, batch);
        assertRefused("audit_log_target_matches_action", sql, staff.getId(), "HOSPITAL_CREATED", null, null, hospital.getId(), batch);

        // An action outside the list is still refused.
        assertRefused("audit_log_action_check", sql, staff.getId(), "STOCK_EDITED", null, null, null, batch);

        // And the older actions still work exactly as before.
        assertThat(jdbc.update(sql, staff.getId(), "ACCOUNT_SUSPENDED", staff.getId(), null, null, null)).isEqualTo(1);
        assertThat(jdbc.update(sql, staff.getId(), "BLOOD_BANK_CREATED", null, bank.getId(), null, null)).isEqualTo(1);
        assertThat(jdbc.update(sql, staff.getId(), "HOSPITAL_CREATED", null, null, hospital.getId(), null)).isEqualTo(1);

        // A write-off entry is as append-only as any other (V12's trigger).
        assertRefused("audit_log is append-only: UPDATE is not permitted",
            "update audit_log set details = 'rewritten' where target_batch_id = ?", batch);
    }

    // ---------- the stock identity and allocation checks ----------

    @Test
    void theStockIdentityQueryFindsExactlyTheBatchesWhoseFiguresDoNotAddUp() {
        // received 10 = remaining 5 + allocated 3 + written off 2.
        UUID balanced = insertBatch(10, 5, IN_30_DAYS);
        insertAllocation(insertContribution(3), balanced, 3);
        insertWriteOff(balanced, 2, "EXPIRED", null);

        // received = remaining with no history: what a MIGRATED batch looks like.
        UUID untouched = insertBatch(7, 7, IN_30_DAYS);

        // One unit missing, and one unit too many.
        UUID unitsVanished = insertBatch(10, 4, IN_30_DAYS);
        insertAllocation(insertContribution(3), unitsVanished, 3);
        insertWriteOff(unitsVanished, 2, "DAMAGED", null);
        UUID unitsAppeared = insertBatch(10, 6, IN_30_DAYS);
        insertAllocation(insertContribution(3), unitsAppeared, 3);
        insertWriteOff(unitsAppeared, 2, "DAMAGED", null);

        List<UUID> broken = batchRepository.findIdsBreakingStockIdentity();

        assertThat(broken).contains(unitsVanished, unitsAppeared).doesNotContain(balanced, untouched);
    }

    @Test
    void theAllocationCheckFindsContributionsWhoseBatchesDoNotAddUp() {
        UUID matched = insertContribution(4);
        insertAllocation(matched, insertBatch(3, 0, IN_30_DAYS), 3);
        insertAllocation(matched, insertBatch(5, 4, IN_30_DAYS.plusDays(2)), 1);

        UUID shortOfUnits = insertContribution(4);
        insertAllocation(shortOfUnits, insertBatch(5, 3, IN_30_DAYS), 2);

        // Made before batch stock existed: no allocations, and not a mismatch.
        UUID historical = insertContribution(4);

        assertThat(allocationRepository.findContributionIdsWithMismatchedAllocations())
            .contains(shortOfUnits)
            .doesNotContain(matched, historical);
    }

    // ---------- issue order and the FEFO index ----------

    @Test
    void postgresOrdersBatchesExactlyAsTheAllocatorDoes() {
        // Mixed expiry dates, several ties, and ids chosen so that Java's signed
        // UUID comparison would disagree with PostgreSQL's unsigned one.
        List<Candidate> batches = new ArrayList<>();
        List<UUID> ids = new ArrayList<>(List.of(
            UUID.fromString("80000000-0000-0000-0000-000000000000"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            UUID.fromString("00000000-0000-0000-8000-000000000000"),
            UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff")));
        for (int i = 0; i < 15; i++) {
            ids.add(UUID.randomUUID());
        }
        // A received_at far in the past marks this test's rows, so the query below
        // cannot pick up any other batch.
        for (int i = 0; i < ids.size(); i++) {
            LocalDate expiry = IN_30_DAYS.plusDays(i % 3);
            jdbc.update("""
                insert into blood_inventory_batches (id, blood_bank_id, blood_type, units_received,
                                                     units_remaining, expiry_date, source, received_at)
                values (?, ?, 'AB-', 1, 1, ?, 'MANUAL', timestamptz '2001-01-01 00:00:00+00')
                """, ids.get(i), otherBank.getId(), expiry);
            batches.add(new Candidate(ids.get(i), expiry, 1));
        }

        List<UUID> postgresOrder = jdbc.queryForList("""
            select id from blood_inventory_batches
             where received_at = timestamptz '2001-01-01 00:00:00+00'
             order by expiry_date, id
            """, UUID.class);
        List<UUID> javaOrder = batches.stream().sorted(FefoAllocator.ISSUE_ORDER).map(Candidate::batchId).toList();

        assertThat(postgresOrder).containsExactlyElementsOf(javaOrder);
        // And not merely by luck: Java's natural order would be different.
        assertThat(batches.stream().sorted(Comparator.comparing(Candidate::expiryDate)
            .thenComparing(Candidate::batchId)).map(Candidate::batchId).toList()).isNotEqualTo(javaOrder);
    }

    @Test
    void onlyTheNamedIndexesExistAndTheFefoLockQueryCanUseIt() {
        List<String> indexes = jdbc.queryForList("""
            select indexname from pg_indexes
             where tablename in ('blood_inventory_batches', 'contribution_allocations', 'batch_write_offs')
             order by indexname
            """, String.class);
        // Primary keys and the two unique constraints make their own indexes; the
        // only two added on purpose are idx_batches_fefo and idx_allocations_batch.
        assertThat(indexes).containsExactlyInAnyOrder(
            "batch_write_offs_pkey",
            "blood_inventory_batches_pkey",
            "contribution_allocations_pkey",
            "idx_allocations_batch",
            "idx_batches_fefo",
            "uq_allocation_contribution_batch",
            "uq_batches_donation");

        String fefo = jdbc.queryForObject(
            "select indexdef from pg_indexes where indexname = 'idx_batches_fefo'", String.class);
        assertThat(fefo).contains("(blood_bank_id, blood_type, expiry_date, id)").contains("WHERE (units_remaining > 0)");

        // With sequential scans off for this transaction, the plan shows the lock query can use the partial index.
        jdbc.execute("set local enable_seqscan = off");
        String plan = String.join("\n", jdbc.queryForList("""
            explain select id from blood_inventory_batches
             where blood_bank_id = '%s' and blood_type = 'O-'
               and units_remaining > 0 and expiry_date >= current_date
             order by expiry_date, id
             for update
            """.formatted(bank.getId()), String.class));
        assertThat(plan).contains("idx_batches_fefo");
    }

    // ---------- the JPA entities map onto the tables ----------

    @Test
    void theEntitiesRoundTripThroughTheirRepositoriesAndKeepTheIdentity() {
        BloodInventoryBatch batch = batchRepository.save(BloodInventoryBatch.fromDonation(donation, IN_30_DAYS));
        entityManager.flush();

        // Issue one unit, write off the other: the identity must still hold.
        batch.deductUnits(1);
        BloodRequestContribution contribution = contributionRepository.save(contribution(1));
        allocationRepository.save(ContributionAllocation.of(contribution, batch, 1));
        batch.deductUnits(1);
        writeOffRepository.save(BatchWriteOff.record(batch, 1, WriteOffReason.EXPIRED, "  ", staff));
        entityManager.flush();
        entityManager.clear();

        BloodInventoryBatch reloaded = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(reloaded.getSource()).isEqualTo(BatchSource.DONATION);
        assertThat(reloaded.getDonation().getId()).isEqualTo(donation.getId());
        assertThat(reloaded.getBloodBank().getId()).isEqualTo(bank.getId());
        assertThat(reloaded.getBloodType()).isEqualTo(BloodType.O_NEGATIVE);
        assertThat(reloaded.getUnitsReceived()).isEqualTo(2);
        assertThat(reloaded.getUnitsRemaining()).isZero();
        assertThat(reloaded.getExpiryDate()).isEqualTo(IN_30_DAYS);
        assertThat(reloaded.getReceivedAt()).isNotNull();
        assertThat(reloaded.getLastUpdated()).isNotNull();
        assertThat(reloaded.getExpiryNoticedAt()).isNull();

        assertThat(batchRepository.findIdsBreakingStockIdentity()).doesNotContain(batch.getId());
        assertThat(allocationRepository.findContributionIdsWithMismatchedAllocations()).doesNotContain(contribution.getId());

        UUID writeOffId = jdbc.queryForObject(
            "select id from batch_write_offs where batch_id = ?", UUID.class, batch.getId());
        BatchWriteOff writeOff = writeOffRepository.findById(writeOffId).orElseThrow();
        assertThat(writeOff.getNote()).as("a blank note is stored as no note").isNull();
        assertThat(writeOff.getReason()).isEqualTo(WriteOffReason.EXPIRED);
        assertThat(writeOff.getWrittenOffBy().getId()).isEqualTo(staff.getId());
        assertThat(writeOff.getWrittenOffAt()).isNotNull();
    }

    // ---------- helpers ----------

    /**
     * Runs a statement that must be refused naming expected, inside a savepoint so the transaction continues.
     */
    private void assertRefused(String expected, String sql, Object... args) {
        jdbc.execute("savepoint expected_refusal");
        try {
            assertThatThrownBy(() -> jdbc.update(sql, args)).hasStackTraceContaining(expected);
        } finally {
            jdbc.execute("rollback to savepoint expected_refusal");
        }
    }

    private static String insertBatchSql() {
        return """
            insert into blood_inventory_batches (blood_bank_id, blood_type, units_received, units_remaining,
                                                 expiry_date, source, donation_id)
            values (?, ?, ?, ?, ?, ?, ?)
            """;
    }

    private UUID insertBatch(int received, int remaining, LocalDate expiry) {
        return jdbc.queryForObject("""
            insert into blood_inventory_batches (blood_bank_id, blood_type, units_received, units_remaining,
                                                 expiry_date, source)
            values (?, 'O-', ?, ?, ?, 'MANUAL')
            returning id
            """, UUID.class, bank.getId(), received, remaining, expiry);
    }

    private UUID insertContribution(int units) {
        return jdbc.queryForObject("""
            insert into blood_request_contributions (blood_request_id, blood_bank_id, units_contributed)
            values (?, ?, ?)
            returning id
            """, UUID.class, request.getId(), bank.getId(), units);
    }

    private UUID insertAllocation(UUID contribution, UUID batch, int units) {
        return jdbc.queryForObject("""
            insert into contribution_allocations (contribution_id, batch_id, units_allocated)
            values (?, ?, ?)
            returning id
            """, UUID.class, contribution, batch, units);
    }

    private UUID insertWriteOff(UUID batch, int units, String reason, String note) {
        return jdbc.queryForObject("""
            insert into batch_write_offs (batch_id, units, reason, note, written_off_by_user_id)
            values (?, ?, ?, ?, ?)
            returning id
            """, UUID.class, batch, units, reason, note, staff.getId());
    }

    private BloodRequestContribution contribution(int units) {
        BloodRequestContribution contribution = new BloodRequestContribution();
        contribution.setBloodRequest(request);
        contribution.setBloodBank(bank);
        contribution.setUnitsContributed(units);
        return contribution;
    }

    private User user(String fullName, Role role, BloodBank bloodBank) {
        User user = new User();
        user.setFullName(fullName);
        // Donors may have no email (Business Rule 18); staff must have one.
        user.setEmail(role == Role.DONOR ? null : "batch.it." + UUID.randomUUID() + "@example.com");
        user.setPhone("+232" + String.format("%08d", 70_000_000L + PHONE_SEQUENCE.incrementAndGet() % 10_000_000L));
        // Never signed in with: these tests act in SQL, not through the API.
        user.setPasswordHash("not-a-usable-bcrypt-hash");
        user.setRole(role);
        user.setBloodBank(bloodBank);
        return userRepository.save(user);
    }
}
