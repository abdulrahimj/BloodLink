package com.bloodlink.admin.dto;

import java.util.List;

/**
 * One page of the User Accounts table (GET /api/admin/users), in the same envelope as the Audit Log and Donors roster.
 * totalElements and totalPages count only the accounts matching the current search and filters.
 */
public record AdminUserPageResponse(
    List<AdminUserResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
