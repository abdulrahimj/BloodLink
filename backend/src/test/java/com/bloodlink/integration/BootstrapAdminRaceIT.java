package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

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

import com.bloodlink.admin.bootstrap.BootstrapAdminService;
import com.bloodlink.admin.bootstrap.BootstrapAdminService.Outcome;
import com.bloodlink.admin.bootstrap.BootstrapAdminSettings;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.AuditLogRepository;
import com.bloodlink.repository.UserRepository;

/**
 * Business Rule 27: servers starting at the same moment never create two first administrators, even with different
 * settings. Needs real commits, so it creates and drops its own database. Run -Dtest=BootstrapAdminRaceIT.
 */
@SpringBootTest
@ActiveProfiles("test")
// The context's connection pool points at a database this class drops, so it must not be reused.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BootstrapAdminRaceIT {

    private static final String DATABASE = "bloodlink_bootstrap_race_test";
    private static final int SERVERS = 6;

    @Autowired private BootstrapAdminService bootstrapAdminService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void useAThrowawayDatabase(DynamicPropertyRegistry registry) throws Exception {
        runOnServer("drop database if exists " + DATABASE + " with (force)");
        runOnServer("create database " + DATABASE);
        registry.add("spring.datasource.url", () -> TestDatabaseLogin.serverUrl().replace("/postgres", "/" + DATABASE));
    }

    @AfterAll
    static void dropThrowawayDatabase() throws Exception {
        runOnServer("drop database if exists " + DATABASE + " with (force)");
    }

    private static void runOnServer(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                TestDatabaseLogin.serverUrl(), TestDatabaseLogin.username(), TestDatabaseLogin.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void concurrentStartsCreateExactlyOneAdministratorWhetherTheirSettingsDifferOrMatch() throws Exception {
        assertThat(userRepository.existsByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).isFalse();

        // Different numbers and emails: no unique constraint can stop a second one, only the advisory lock can.
        List<Outcome> first = race(i -> new BootstrapAdminSettings(
            "07690000" + i, "Admin " + i, "admin" + i + "@example.com", "Temporary-password-" + i));
        assertExactlyOneCreated(first, 1);

        // A fresh "no active administrator" state, then every server with identical settings.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)
                .forEach(admin -> admin.setStatus(AccountStatus.SUSPENDED)));
        List<Outcome> second = race(i -> new BootstrapAdminSettings(
            "076999000", "Same Admin", "same@example.com", "Temporary-password-same-1"));
        assertExactlyOneCreated(second, 2);
    }

    /** Starts every "server" at one barrier, each in its own transaction, and returns what each one did. */
    private List<Outcome> race(IntFunction<BootstrapAdminSettings> settingsFor) throws Exception {
        CyclicBarrier start = new CyclicBarrier(SERVERS);
        ExecutorService pool = Executors.newFixedThreadPool(SERVERS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < SERVERS; i++) {
                BootstrapAdminSettings settings = settingsFor.apply(i);
                Callable<Outcome> server = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return bootstrapAdminService.createFirstAdminIfNeeded(settings);
                };
                futures.add(pool.submit(server));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertExactlyOneCreated(List<Outcome> outcomes, long adminAccountsInTotal) {
        assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.CREATED).hasSize(1);
        // Every other server waited for the lock and then found that administrator.
        assertThat(outcomes).filteredOn(outcome -> outcome != Outcome.CREATED)
            .hasSize(SERVERS - 1)
            .containsOnly(Outcome.ADMIN_EXISTS);
        assertThat(userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE)).hasSize(1);
        assertThat(userRepository.countGroupedByRole()).filteredOn(row -> row.getRole() == Role.ADMIN)
            .singleElement().satisfies(row -> assertThat(row.getTotal()).isEqualTo(adminAccountsInTotal));
        assertThat(auditLogRepository.findAllNewestFirst(org.springframework.data.domain.PageRequest.of(0, 50))
            .getTotalElements()).isEqualTo(adminAccountsInTotal);
    }
}
