package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
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

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.Hospital;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.RequestStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.domain.enums.UrgencyLevel;
import com.bloodlink.inventory.InventoryService;
import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.BloodRequestContributionRepository;
import com.bloodlink.repository.BloodRequestRepository;
import com.bloodlink.repository.HospitalRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.request.RequestService;
import com.bloodlink.request.dto.BloodRequestResponse;
import com.bloodlink.request.dto.CreateRequestPayload;
import com.bloodlink.request.dto.RequestAction;
import com.bloodlink.request.dto.RequestActionPayload;
import com.bloodlink.security.AuthenticatedUser;

/**
 * The race at the centre of Business Rule 20: two blood banks contributing to the
 * same request at the same instant must never be able to over-fulfil it.
 *
 * <h2>Why this test has its own database</h2>
 * Every other integration test in this project is {@code @Transactional} and rolls
 * back — which is exactly what makes re-running them safe, and is the guarantee
 * CLAUDE.md's convention protects. This one cannot be: two transactions racing for
 * a row lock have to be two *real* transactions, committing against rows the other
 * one can see, and a test that never commits can produce neither. Rather than break
 * that guarantee on the shared {@code bloodlink_test} fixture, this class follows
 * the convention's other branch — "create a throwaway database for the run" — and
 * does it automatically: the database below is created before the Spring context
 * starts, built from scratch by Flyway, and dropped when the class finishes. It
 * cannot damage anything, and nothing is left behind to clean up.
 *
 * <h2>What is proven</h2>
 * Both threads call the real {@code RequestService.act}, each in its own
 * transaction, released together by a barrier so they collide on purpose. The
 * pessimistic write lock on the request row (BloodRequestRepository.findByIdForUpdate)
 * is the only thing standing between them and a hospital being sent more blood than
 * it asked for, out of two banks' shelves.
 *
 * <pre>{@code .\mvnw.cmd test -Dtest=PartialFulfilmentRaceIT}</pre>
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
    @Autowired private UserRepository userRepository;
    @Autowired private BloodBankRepository bloodBankRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private BloodRequestRepository bloodRequestRepository;
    @Autowired private BloodRequestContributionRepository contributionRepository;

    // ---------- the throwaway database ----------

    /**
     * Creates the database and points this context's datasource at it, before the
     * context — and therefore Flyway — starts. Dropped first in case an earlier
     * crashed run left one behind, so re-running is always safe.
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

    /** Runs one statement against the server's own {@code postgres} database, using
     *  the credentials the test profile already declares — so there is no second
     *  copy of them here to drift. */
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
     * Two banks, each holding enough to cover the whole request, both contributing
     * the full amount at the same moment.
     *
     * Exactly one contribution can be accepted. Without the row lock both would read
     * "4 needed", both would deduct 4 units of real blood, and the hospital would be
     * sent 8 units for a request for 4 — the failure this rule exists to prevent.
     */
    @Test
    void twoBanksContributingTheWholeAmountAtOnceCannotBothSucceed() throws Exception {
        Fixture fixture = new Fixture(4, 5, 5);

        List<Outcome> outcomes = raceContributions(fixture, 4, 4);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded()).singleElement()
            // The loser is told why, in words an operator can act on. Which of the
            // two guards catches it depends on how far the winner had got when the
            // lock was released — both are the same refusal, and both are correct.
            .satisfies(outcome -> assertThat(outcome.failure())
                .containsAnyOf("cannot take more units", "covered in full"));

        assertTotalContributedIsExactly(fixture, 4);
        // And only one bank's shelf moved — by exactly the units it gave.
        assertThat(unitsTakenFromEachBank(fixture)).containsExactlyInAnyOrder(4, 0);
    }

    /**
     * The same collision, but where both banks genuinely have something to give: a
     * request for 6 units, each bank offering 4.
     *
     * Both contributions are accepted — this is cooperative fulfilment working — but
     * the second is capped at the 2 units still outstanding rather than taking the 4
     * it offered. The total contributed is exactly 6, never 8.
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
     * One bank's contribution, in its own thread and therefore its own transaction —
     * which is the whole point: the transaction boundary is RequestService's own
     * {@code @Transactional}, entered through the Spring proxy exactly as a real HTTP
     * request would enter it.
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

    /** The figure the whole rule comes down to: the units a request has received can
     *  never exceed the units it asked for — and when they meet it exactly, the
     *  request is closed rather than left looking open. */
    private void assertTotalContributedIsExactly(Fixture fixture, int expected) {
        assertThat(contributionRepository.sumUnitsForRequest(fixture.requestId)).isEqualTo(expected);
        var request = bloodRequestRepository.findById(fixture.requestId).orElseThrow();
        assertThat(expected).isEqualTo(request.getUnitsRequested());
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FULFILLED);
    }

    /**
     * How many units each bank actually lost, measured against what it held once the
     * fixture was set up.
     *
     * Deltas rather than absolute figures because this class does not roll back —
     * that is the whole point of it — so each test starts with whatever the previous
     * one left on the shelf. Asserting "5 units remain" would pass once and then
     * break the moment another test ran first, which is exactly the kind of
     * order-dependent test that is worse than no test.
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

    // ---------- fixture ----------

    /**
     * Two stocked banks and one hospital request, all committed before the race
     * starts — the threads have to be able to see them.
     *
     * Everything is written through the real services and repositories, each of
     * which commits on its own; nothing here is left open in an enclosing
     * transaction, because an uncommitted row is invisible to the racing threads.
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

    /** These accounts never sign in — this test calls the services directly rather
     *  than over HTTP — so the stored hash only has to be a non-null value of the
     *  right shape, not a password anybody could use. */
    private static final String UNUSABLE_PASSWORD_HASH =
        "$2a$10$notusedfornotusedfornotusedfornotusedfornotusedfor12";

    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger();
}
