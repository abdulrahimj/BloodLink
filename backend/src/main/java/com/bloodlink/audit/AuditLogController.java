package com.bloodlink.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.audit.dto.AuditLogPageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The Audit Log page's data (Business Rule 17): one GET only, since entries are written by the actions they record
 * and are permanent; any other method is a 405. ADMIN-only via SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;

    AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * One page of entries, newest first: page 0-based (default 0), size default 20 and max 100; bad values are a 400.
     * No @Validated on this class; see AppealController.reach for why.
     */
    @GetMapping
    public AuditLogPageResponse list(
        @RequestParam(defaultValue = "0")
        @Min(value = 0, message = "page cannot be negative")
        int page,
        @RequestParam(defaultValue = "20")
        @Min(value = 1, message = "size must be at least 1")
        @Max(value = 100, message = "size cannot exceed 100")
        int size
    ) {
        return auditLogService.list(page, size);
    }
}
