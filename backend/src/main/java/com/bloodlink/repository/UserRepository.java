package com.bloodlink.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Phone is the login credential, so this is the lookup every authentication runs. */
    Optional<User> findByPhone(String phone);

    /**
     * Just the status column by primary key: what JwtAuthenticationFilter checks on every request
     * to enforce a suspension immediately.
     */
    @Query("select u.status from User u where u.id = :id")
    Optional<AccountStatus> findStatusById(@Param("id") UUID id);

    /** Pre-flight uniqueness checks at registration, so we can return a friendly
     *  409 instead of letting the database's unique constraints surface as a 500. */
    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    /**
     * The staff accounts attached to one facility, i.e. who that facility's notifications go to;
     * the role is in the query so it says what it means.
     */
    List<User> findByRoleAndBloodBankId(Role role, UUID bloodBankId);

    List<User> findByRoleAndHospitalId(Role role, UUID hospitalId);

    /**
     * Every account with one role; only the fallback when no bank can cover a new request,
     * so the alert goes to all bank staff. Not for user-facing listings.
     */
    List<User> findByRole(Role role);

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
