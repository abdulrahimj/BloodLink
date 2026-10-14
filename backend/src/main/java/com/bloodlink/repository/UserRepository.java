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
     * Just the status column, by primary key — what JwtAuthenticationFilter checks
     * on every authenticated request to enforce a suspension immediately rather
     * than only at the next login.
     *
     * Deliberately not the whole User: the filter runs on every request and needs
     * nothing else from this row, so selecting one column off an indexed primary
     * key is the cheapest honest way to ask "is this account still allowed to act
     * right now?" without pulling back facility associations it will not use.
     */
    @Query("select u.status from User u where u.id = :id")
    Optional<AccountStatus> findStatusById(@Param("id") UUID id);

    /** Pre-flight uniqueness checks at registration, so we can return a friendly
     *  409 instead of letting the database's unique constraints surface as a 500. */
    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    /**
     * The staff accounts attached to one facility — who a notification about that
     * facility is addressed to.
     *
     * Role is part of the query rather than assumed from the column, because
     * users.blood_bank_id and users.hospital_id are both nullable columns on a
     * shared table: matching on the id alone would be correct today only because
     * no other role is ever given one, which is a rule enforced nowhere in the
     * database. Naming the role makes the query say what it means.
     */
    List<User> findByRoleAndBloodBankId(Role role, UUID bloodBankId);

    List<User> findByRoleAndHospitalId(Role role, UUID hospitalId);

    /**
     * Every account holding one role, regardless of facility.
     *
     * Used only as the fallback for a new-request alert that no bank currently holds
     * the stock to cover: with no capable bank to single out, the alert goes to all
     * bank staff rather than to nobody. Not a general-purpose listing — anything
     * user-facing should be scoped to a facility.
     */
    List<User> findByRole(Role role);

    /**
     * The search and filter conditions shared by {@link #searchAccounts} and its count
     * query, so a page's rows and its totals can never disagree about what matched.
     *
     * {@code :q} is null when nothing was typed (no search); otherwise the account
     * matches if the text is in its name or email (case-insensitive, via
     * {@code :textPattern}) or in its phone ({@code :phonePattern}, which has already
     * had a local "0…" turned into "+232…"). Both patterns come from LikePattern, which
     * escapes {@code %} and {@code _} with a backslash — hence {@code escape '\'}.
     * A donor with no email (Business Rule 18) simply never matches on email.
     * {@code :role} and {@code :status} are null for "any".
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
     * One page of the Admin Console's "User Accounts" table: search, role filter,
     * status filter and paging in a single query, so the database returns only the
     * rows on screen and the count query counts only what matched.
     *
     * The order comes from the {@link Pageable}'s Sort, which AdminUserService builds
     * from a fixed list of four properties plus {@code id} as a tie-breaker — never
     * from text in the request. That is also why this query has no ORDER BY of its own.
     *
     * The facilities are fetch-joined because every row shows one and open-in-view is
     * off; both are to-one associations, so paging still applies to whole rows. Left
     * joins keep donors and administrators, who have neither.
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
     * How many accounts hold each role, in one grouped query — the Admin Console's
     * "Total Users" and "Registered Donors" cards and the counts on its role filter
     * buttons. Replaces downloading every account just to count them.
     */
    @Query("select u.role as role, count(u) as total from User u group by u.role")
    List<RoleCount> countGroupedByRole();

    /** One role's account count, from {@link #countGroupedByRole}. */
    interface RoleCount {
        Role getRole();
        long getTotal();
    }
}
