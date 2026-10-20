package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bloodlink.domain.BatchWriteOff;
import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.BloodInventoryBatch;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.BloodType;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.domain.enums.WriteOffReason;
import com.bloodlink.inventory.BatchWriteOffService;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.WriteOffBatchRequest;
import com.bloodlink.repository.BatchWriteOffRepository;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodInventoryBatchRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.ContributionAllocationRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.RequestService;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;

/**
 * Business Rule 20's race: two banks contributing at the same instant must never over-fulfil a request.
 * Needs real commits, so it creates and drops its own throwaway database. Run -Dtest=PartialFulfilmentRaceIT.
 */
@SpringBootTest
@ActiveProfiles("test")
// The context holds a connection pool to a database this class drops on the way
// out, so it must not be handed to any later test class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PartialFulfilmentRaceIT {

    /** Created before the context starts and dropped in {@link #dropThrowawayDatabase()}. */
    private static final String DATABASE = "bloodlink_race_test";
    private static final String GROUP = "AB-";

    @Autowired private RequestService requestService;
    @Autowired private InventoryService inventoryService;
    @Autowired private BatchWriteOffService batchWriteOffService;
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodRequestRepository bloodRequestRepository;
    @Autowired private BloodRequestContributionRepository contributionRepository;
    @Autowired private BloodInventoryBatchRepository batchRepository;
    @Autowired private ContributionAllocationRepository allocationRepository;
    @Autowired private BatchWriteOffRepository writeOffRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // ---------- the throwaway database ----------

    /**
     * Creates the throwaway database (dropping any leftover first) and points the datasource at it before Flyway runs.
     */
    @DynamicPropertySource
    static void useAThrowawayDatabase(DynamicPropertyRegistry registry) throws Exception {
        runOnServer("drop database if exists " + DATABASE + " with (force)");
        runOnServer("create database " + DATABASE);
        registry.add("spring.datasource.url", () -> serverUrl().replace("/postgres", "/" + DATABASE));
    }

    @AfterAll
    static void dropThrowawayDatabase() throws Exception {
        // "with (force)" closes the pool's still-open connections; without it the
        // drop fails while the Spring context is alive.
        runOnServer("drop database if exists " + DATABASE + " with (force)");
    }

    /** Runs one statement against the server's postgres database with the test profile's own credentials. */
    private static void runOnServer(String sql) throws Exception {
        Properties properties = testProperties();
        try (Connection connection = DriverManager.getConnection(
                serverUrl(),
                properties.getProperty("spring.datasource.username"),
                properties.getProperty("spring.datasource.password"));
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String serverUrl() {
        String testUrl = testProperties().getProperty("spring.datasource.url");
        return testUrl.substring(0, testUrl.lastIndexOf('/')) + "/postgres";
    }

    private static Properties testProperties() {
        Properties properties = new Properties();
        try (InputStream in = PartialFulfilmentRaceIT.class.getResourceAsStream("/application-test.properties")) {
            properties.load(in);
        } catch (Exception cannotRead) {
            throw new IllegalStateException("application-test.properties is required for this test", cannotRead);
        }
        return properties;
    }

    // ---------- the race ----------

    /**
     * Two banks each contributing the full amount at once: exactly one is accepted, never 8 units for a request for 4.
     */
    @Test
    void twoBanksContributingTheWholeAmountAtOnceCannotBothSucceed() throws Exception {
        Fixture fixture = new Fixture(4, 5, 5);

        List<Outcome> outcomes = raceContributions(fixture, 4, 4);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded()).singleElement()
            // The loser is told why; which guard catches it depends on timing, and both are correct.
            .satisfies(outcome -> assertThat(outcome.failure())
                .containsAnyOf("cannot take more units", "covered in full"));

        assertTotalContributedIsExactly(fixture, 4);
        // And only one bank's shelf moved — by exactly the units it gave.
        assertThat(unitsTakenFromEachBank(fixture)).containsExactlyInAnyOrder(4, 0);
    }

    /**
     * Both banks can help (6 needed, 4 each): both accepted, the second capped at the 2 left, so 6 total, never 8.
     */
    @Test
    void whenBothBanksCanHelpTheSecondContributionIsCappedAtWhatIsLeft() throws Exception {
        Fixture fixture = new Fixture(6, 5, 5);

        List<Outcome> outcomes = raceContributions(fixture, 4, 4);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertTotalContributedIsExactly(fixture, 6);
        assertThat(contributionRepository.findForRequests(List.of(fixture.requestId)))
            .extracting(contribution -> contribution.getUnitsContributed())
            // One bank gave the 4 it offered, the other the 2 that were left.
            .containsExactlyInAnyOrder(4, 2);
        // And the shelves match the records unit for unit.
        assertThat(unitsTakenFromEachBank(fixture)).containsExactlyInAnyOrder(4, 2);
    }

    /** Both banks contribute at the same instant, released together. */
    private List<Outcome> raceContributions(Fixture fixture, int unitsFromA, int unitsFromB) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = List.of(
                threads.submit(contributionTask(fixture, fixture.bankAStaff, unitsFromA, startTogether)),
                threads.submit(contributionTask(fixture, fixture.bankBStaff, unitsFromB, startTogether))
            );

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * One bank's contribution in its own thread and transaction, entered through the Spring proxy like a real request.
     */
    private Callable<Outcome> contributionTask(Fixture fixture, User staff, int units, CyclicBarrier barrier) {
        AuthenticatedUser principal = new AuthenticatedUser(
            staff.getId(), staff.getPhone(), staff.getPasswordHash(), Role.BLOOD_BANK_STAFF);
        return () -> {
            barrier.await(30, TimeUnit.SECONDS);
            try {
                requestService.act(
                    fixture.requestId,
                    new RequestActionPayload(RequestAction.CONTRIBUTE, null, units),
                    principal
                );
                return new Outcome(true, null);
            } catch (RuntimeException refused) {
                return new Outcome(false, String.valueOf(refused.getMessage()));
            }
        };
    }

    /** Whether one racing contribution was accepted, and why not if it was not. */
    private record Outcome(boolean succeeded, String failure) {}

    /** Units received can never exceed units asked for, and a request met exactly is closed. */
    private void assertTotalContributedIsExactly(Fixture fixture, int expected) {
        assertThat(contributionRepository.sumUnitsForRequest(fixture.requestId)).isEqualTo(expected);
        var request = bloodRequestRepository.findById(fixture.requestId).orElseThrow();
        assertThat(expected).isEqualTo(request.getUnitsRequested());
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FULFILLED);
    }

    /**
     * Units each bank actually lost since the fixture was set up, as deltas
     * because this class never rolls back.
     */
    private List<Integer> unitsTakenFromEachBank(Fixture fixture) {
        return List.of(
            fixture.bankAStockAtStart - unitsInStock(fixture.bankA.getId()),
            fixture.bankBStockAtStart - unitsInStock(fixture.bankB.getId())
        );
    }

    private int unitsInStock(UUID bankId) {
        return inventoryService.listInventory(bankId).stream()
            .filter(row -> row.bloodType().equals(GROUP))
            .findFirst()
            .orElseThrow()
            .unitsAvailable();
    }

    // ---------- batch-level stock: the FEFO lock under contention (Business Rule 24) ----------

    /**
     * One bank, two requests, stock in two batches (3 + 3), two simultaneous contributions of 4:
     * the batch locks serialise them, so exactly one succeeds.
     */
    @Test
    void oneBankTwoRequestsStockInTwoBatchesExactlyOneContributionSucceeds() throws Exception {
        String group = "B-";
        Shelf shelf = new Shelf(group);
        UUID earlier = shelf.addBatch(3, 5);
        UUID later = shelf.addBatch(3, 30);
        UUID first = shelf.raiseRequest(4);
        UUID second = shelf.raiseRequest(4);

        List<Outcome> outcomes = raceOnShelf(shelf, List.of(first, second), 4);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).singleElement()
            .satisfies(o -> assertThat(o.failure()).contains("2 units available", "4 being contributed"));
        assertThat(remaining(earlier)).isZero();
        assertThat(remaining(later)).isEqualTo(2);
        // The winner's units are traced to both batches, earliest first: 3 + 1.
        assertThat(allocationRepository.findForBatch(earlier)).extracting(a -> a.getUnitsAllocated()).containsExactly(3);
        assertThat(allocationRepository.findForBatch(later)).extracting(a -> a.getUnitsAllocated()).containsExactly(1);
        assertThat(contributionRepository.sumUnitsForRequest(first) + contributionRepository.sumUnitsForRequest(second))
            .isEqualTo(4);
        assertLedgersBalance();
    }

    /**
     * Eight 1-unit contributions over 5 units in three batches: exactly 5 succeed, the shelf ends at 0,
     * and nothing deadlocks, since batches are always locked in the same order.
     */
    @Test
    void eightThreadsTakingOneUnitEachFromFiveUnitsOverThreeBatchesExactlyFiveSucceedAndNoneDeadlocks() throws Exception {
        String group = "A+";
        Shelf shelf = new Shelf(group);
        List<UUID> batches = List.of(shelf.addBatch(2, 3), shelf.addBatch(2, 10), shelf.addBatch(1, 20));
        List<UUID> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(shelf.raiseRequest(1));
        }

        long started = System.nanoTime();
        List<Outcome> outcomes = raceOnShelf(shelf, requests, 1);
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcomes).hasSize(8);
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(5);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).hasSize(3)
            .allSatisfy(o -> assertThat(o.failure()).contains("0 units available").doesNotContainIgnoringCase("deadlock"));
        for (UUID batch : batches) {
            assertThat(remaining(batch)).isZero();
        }
        int contributed = 0;
        for (UUID request : requests) {
            contributed += contributionRepository.sumUnitsForRequest(request);
        }
        assertThat(contributed).isEqualTo(5);
        assertThat(tookMillis).as("all eight finished well within the 30 s timeout").isLessThan(30_000);
        assertLedgersBalance();
    }

    /**
     * A contribution arriving while a write-off holds the batch lock waits, then sees the 2 units really left
     * and is refused for 4.
     */
    @Test
    void aContributionRacingABatchLockWaitsForItAndSeesTheUnitsActuallyLeft() throws Exception {
        String group = "O+";
        Shelf shelf = new Shelf(group);
        UUID batchId = shelf.addBatch(5, 15);
        UUID request = shelf.raiseRequest(4);

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(1);
        try {
            Future<?> holder = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                BloodInventoryBatch batch = batchRepository
                    .lockUsableBatchesInIssueOrder(shelf.bank.getId(), BloodType.fromLabel(group), LocalDate.now(ZoneOffset.UTC))
                    .get(0);
                batch.deductUnits(3);
                writeOffRepository.save(BatchWriteOff.record(batch, 3, WriteOffReason.DAMAGED, null, shelf.staff));
                locked.countDown();
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Outcome outcome = contribute(shelf, request, 4);
            long waitedMillis = (System.nanoTime() - started) / 1_000_000;
            holder.get(30, TimeUnit.SECONDS);

            assertThat(waitedMillis).as("waited for the lock rather than reading past it").isGreaterThanOrEqualTo(1_000);
            assertThat(outcome.succeeded()).isFalse();
            assertThat(outcome.failure()).contains("2 units available", "4 being contributed");
        } finally {
            threads.shutdownNow();
        }
        assertThat(remaining(batchId)).isEqualTo(2);
        assertThat(contributionRepository.sumUnitsForRequest(request)).isZero();
        assertLedgersBalance();
    }

    /**
     * A write-off and a contribution racing for one 5-unit batch: exactly one succeeds, the other re-reads and is refused.
     * Five rounds on fresh banks so it holds whichever side wins.
     */
    @Test
    void aWriteOffAndAContributionRacingForOneBatchNeverTakeMoreUnitsThanItHolds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Shelf shelf = new Shelf("AB+");
            UUID batchId = shelf.addBatch(5, 20);
            UUID request = shelf.raiseRequest(4);

            CyclicBarrier startTogether = new CyclicBarrier(2);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            Outcome contributed;
            Outcome wroteOff;
            try {
                Future<Outcome> contribution = threads.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    return contribute(shelf, request, 4);
                });
                Future<Outcome> writeOff = threads.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    return writeOff(shelf, batchId, 3);
                });
                contributed = contribution.get(30, TimeUnit.SECONDS);
                wroteOff = writeOff.get(30, TimeUnit.SECONDS);
            } finally {
                threads.shutdownNow();
            }

            assertThat(List.of(contributed, wroteOff)).as("round " + round).filteredOn(Outcome::succeeded).hasSize(1);
            if (contributed.succeeded()) {
                // The contribution took 4; the write-off then saw the 1 really left.
                assertThat(wroteOff.failure()).contains("only 1 unit left", "3 units cannot be written off");
            } else {
                // The write-off took 3; the contribution then saw the 2 really left.
                assertThat(contributed.failure()).contains("2 units available", "4 being contributed");
            }

            int taken = (contributed.succeeded() ? 4 : 0) + (wroteOff.succeeded() ? 3 : 0);
            int writtenOff = writeOffRepository.findForBatch(batchId).stream().mapToInt(BatchWriteOff::getUnits).sum();
            assertThat(remaining(batchId)).isEqualTo(5 - taken);
            assertThat(contributionRepository.sumUnitsForRequest(request) + writtenOff).isEqualTo(taken);
            assertLedgersBalance();
        }
    }

    private Outcome writeOff(Shelf shelf, UUID batchId, int units) {
        try {
            batchWriteOffService.writeOff(shelf.bank.getId(), batchId,
                new WriteOffBatchRequest(units, WriteOffReason.DAMAGED.name(), null),
                principalFor(shelf.staff, Role.BLOOD_BANK_STAFF));
            return new Outcome(true, null);
        } catch (RuntimeException refused) {
            return new Outcome(false, String.valueOf(refused.getMessage()));
        }
    }

    /** Every request contributed to at once, one thread each, released together. */
    private List<Outcome> raceOnShelf(Shelf shelf, List<UUID> requestIds, int units) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(requestIds.size());
        ExecutorService threads = Executors.newFixedThreadPool(requestIds.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (UUID requestId : requestIds) {
                futures.add(threads.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    return contribute(shelf, requestId, units);
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    private Outcome contribute(Shelf shelf, UUID requestId, int units) {
        try {
            requestService.act(requestId, new RequestActionPayload(RequestAction.CONTRIBUTE, null, units),
                principalFor(shelf.staff, Role.BLOOD_BANK_STAFF));
            return new Outcome(true, null);
        } catch (RuntimeException refused) {
            return new Outcome(false, String.valueOf(refused.getMessage()));
        }
    }

    private int remaining(UUID batchId) {
        return batchRepository.findById(batchId).orElseThrow().getUnitsRemaining();
    }

    /** Nothing anywhere breaks the stock identity or the allocation-sum rule. */
    private void assertLedgersBalance() {
        assertThat(batchRepository.findIdsBreakingStockIdentity()).isEmpty();
        assertThat(allocationRepository.findContributionIdsWithMismatchedAllocations()).isEmpty();
    }

    /**
     * A committed blood bank of its own for one test, with a staff member and hospital requests, so figures are exact.
     */
    private final class Shelf {
        private final BloodBank bank;
        private final User staff;
        private final User hospitalStaff;
        private final String group;

        private Shelf(String group) {
            this.group = group;
            BloodBank created = new BloodBank();
            created.setName("Race Shelf " + UUID.randomUUID().toString().substring(0, 8));
            created.setLocation("Makeni");
            created.setContactPhone("076 000 000");
            this.bank = bloodBankRepository.save(created);
            this.staff = staff("Shelf Technician", Role.BLOOD_BANK_STAFF, bank, null);
            Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);
            this.hospitalStaff = staff("Shelf Ward Clerk", Role.HOSPITAL_STAFF, null, hospital);
        }

        /** Adds one batch through the real Add New Batch path; returns its id. */
        private UUID addBatch(int units, int daysToExpiry) {
            LocalDate expiry = LocalDate.now(ZoneOffset.UTC).plusDays(daysToExpiry);
            inventoryService.addBatch(bank.getId(), group, new AddBatchRequest(units, expiry),
                principalFor(staff, Role.BLOOD_BANK_STAFF));
            return batchRepository.findLiveForBankAndBloodType(bank.getId(), BloodType.fromLabel(group)).stream()
                .filter(b -> b.getExpiryDate().equals(expiry) && b.getUnitsReceived() == units)
                .findFirst().orElseThrow().getId();
        }

        private UUID raiseRequest(int units) {
            return requestService.createRequest(
                new CreateRequestPayload(group, units, UrgencyLevel.URGENT, "Race ward"),
                principalFor(hospitalStaff, Role.HOSPITAL_STAFF)
            ).id();
        }
    }

    // ---------- fixture ----------

    /**
     * Two stocked banks and one request, all committed before the race so the threads can see them.
     */
    private final class Fixture {
        private final BloodBank bankA;
        private final BloodBank bankB;
        private final User bankAStaff;
        private final User bankBStaff;
        private final UUID requestId;
        /** What each bank holds once this fixture is built — the baseline the stock
         *  assertions measure against, since this class leaves its rows behind. */
        private final int bankAStockAtStart;
        private final int bankBStockAtStart;

        private Fixture(int unitsRequested, int bankAStock, int bankBStock) {
            List<BloodBank> banks = bloodBankRepository.findAllByOrderByNameAsc();
            bankA = banks.get(0);
            bankB = banks.get(1);
            Hospital hospital = hospitalRepository.findAllByOrderByNameAsc().get(0);

            bankAStaff = staff("Bank A Technician", Role.BLOOD_BANK_STAFF, bankA, null);
            bankBStaff = staff("Bank B Technician", Role.BLOOD_BANK_STAFF, bankB, null);
            User hospitalStaff = staff("Ward Clerk", Role.HOSPITAL_STAFF, null, hospital);

            addStock(bankA.getId(), bankAStaff, bankAStock);
            addStock(bankB.getId(), bankBStaff, bankBStock);
            bankAStockAtStart = unitsInStock(bankA.getId());
            bankBStockAtStart = unitsInStock(bankB.getId());

            BloodRequestResponse request = requestService.createRequest(
                new CreateRequestPayload(GROUP, unitsRequested, UrgencyLevel.EMERGENCY, "Theatre 1"),
                principalFor(hospitalStaff, Role.HOSPITAL_STAFF)
            );
            requestId = request.id();
        }

        private void addStock(UUID bankId, User staff, int units) {
            inventoryService.addBatch(
                bankId, GROUP, new AddBatchRequest(units, LocalDate.now().plusDays(30)),
                principalFor(staff, Role.BLOOD_BANK_STAFF)
            );
        }
    }

    private AuthenticatedUser principalFor(User user, Role role) {
        return new AuthenticatedUser(user.getId(), user.getPhone(), user.getPasswordHash(), role);
    }

    private User staff(String fullName, Role role, BloodBank bank, Hospital hospital) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail("race.it." + UUID.randomUUID() + "@example.com");
        user.setPhone("+232" + String.format("%08d", PHONE_SEQUENCE.incrementAndGet() + 76000000L));
        user.setPasswordHash(UNUSABLE_PASSWORD_HASH);
        user.setRole(role);
        user.setBloodBank(bank);
        user.setHospital(hospital);
        return userRepository.save(user);
    }

    /** These accounts never sign in, so the hash only needs the right shape. */
    private static final String UNUSABLE_PASSWORD_HASH =
        "$2a$10$notusedfornotusedfornotusedfornotusedfornotusedfor12";

    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
