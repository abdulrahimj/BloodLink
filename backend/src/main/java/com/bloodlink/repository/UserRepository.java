package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Phone is the login credential, so this is the lookup every authentication runs. */
    Optional<User> findByPhone(String phone);

    /**
     * Status, must-change-password and the password-change time by primary key: what JwtAuthenticationFilter and the
     * STOMP CONNECT check judge, so a suspension, a temporary password or a password change bites at once (27, 32).
     */
    @Query("select new com.bloodlink.repository.AccountAccess(u.status, u.mustChangePassword, u.passwordChangedAt) "
        + "from User u where u.id = :id")
    Optional<AccountAccess> findAccessById(@Param("id") UUID id);

    /** Whether any account of this role is in this status: the first-administrator bootstrap asks it of ACTIVE ADMIN. */
    boolean existsByRoleAndStatus(Role role, AccountStatus status);

    /**
     * Waits for a PostgreSQL advisory lock held until the transaction ends, so two servers starting at once take turns
     * at the first-administrator bootstrap. Cast to text because the lock function itself returns nothing.
     */
    @Query(value = "select cast(pg_advisory_xact_lock(:key) as text)", nativeQuery = true)
    String waitForAdvisoryLock(@Param("key") long key);

    /** Pre-flight uniqueness checks at registration, so we can return a friendly
     *  409 instead of letting the database's unique constraints surface as a 500. */
    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    /**
     * The staff accounts of one facility in one status, i.e. who that facility's notifications go to (always ACTIVE:
     * no notification reaches a suspended account). The role is in the query so it says what it means.
     */
    List<User> findByRoleAndBloodBankIdAndStatus(Role role, UUID bloodBankId, AccountStatus status);

    List<User> findByRoleAndHospitalIdAndStatus(Role role, UUID hospitalId, AccountStatus status);

    /**
     * Every account with one role and status, row-locked until the transaction ends: the last-active-admin check
     * locks the active admins so two suspending each other at once cannot both succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.status = :status")
    List<User> lockByRoleAndStatus(@Param("role") Role role, @Param("status") AccountStatus status);

    /**
     * One account, row-locked until the transaction ends: two administrators resetting the same password at once
     * take turns, so the temporary password shown last is the one that works (Business Rule 31).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);

    /**
     * Every account with one role and status; only the fallback when no bank can cover a new request,
     * so the alert goes to all active bank staff. Not for user-facing listings.
     */
    List<User> findByRoleAndStatus(Role role, AccountStatus status);

    /**
     * Search and filter conditions shared by searchAccounts and its count query, so rows and totals agree:
     * escaped name/email and phone patterns, plus optional role and status.
     */
    String ACCOUNT_FILTER = """
        (:q is null
              or lower(u.fullName) like :textPattern escape '\\'
              or lower(u.email) like :textPattern escape '\\'
              or u.phone like :phonePattern escape '\\')
          and (:role is null or u.role = :role)
          and (:status is null or u.status = :status)
        """;

    /**
     * One page of the User Accounts table with search, filters and paging in one query; the order comes from
     * the Pageable built from AdminUserService's whitelist. Facilities left-fetch-joined (to-one).
     */
    @Query(
        value = "select u from User u left join fetch u.bloodBank left join fetch u.hospital where " + ACCOUNT_FILTER,
        countQuery = "select count(u) from User u where " + ACCOUNT_FILTER
    )
    Page<User> searchAccounts(
        @Param("q") String q,
        @Param("textPattern") String textPattern,
        @Param("phonePattern") String phonePattern,
        @Param("role") Role role,
        @Param("status") AccountStatus status,
        Pageable pageable
    );

    /**
     * Account counts per role in one grouped query, for the console's user cards and role buttons.
     */
    @Query("select u.role as role, count(u) as total from User u group by u.role")
    List<RoleCount> countGroupedByRole();

    /** One role's account count, from {@link #countGroupedByRole}. */
    interface RoleCount {
        Role getRole();
        long getTotal();
    }
}
