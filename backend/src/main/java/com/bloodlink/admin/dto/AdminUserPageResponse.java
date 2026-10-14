package com.bloodlink.admin.dto;

import java.util.List;

/**
 * One page of the Admin Console's "User Accounts" table — the shape
 * {@code GET /api/admin/users} returns.
 *
 * The same envelope as the Audit Log and the Donors roster, for the same reason:
 * Spring Data's own {@code Page} carries internal fields that are not part of this
 * API. {@code totalElements} and {@code totalPages} count only the accounts that
 * match the current search and filters.
 */
public record AdminUserPageResponse(
    List<AdminUserResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
