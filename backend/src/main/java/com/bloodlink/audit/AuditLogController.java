package com.bloodlink.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.audit.dto.AuditLogEntryResponse;

/**
 * The Audit Log page's data — Business Rule 17.
 *
 * One GET, and deliberately nothing else: no POST (entries are written only as a
 * side effect of the actions they record, never on request), and no PUT, PATCH or
 * DELETE (an entry, once written, is permanent). A request with any other method
 * gets 405 Method Not Allowed — AuditLogIT asserts that, and also checks every
 * mapped route in the application to confirm no other controller exposes one.
 *
 * Under /api/admin/**, which SecurityConfig restricts to ADMIN, so there is no role
 * check to repeat here.
 */
@RestController
@RequestMapping("/api/admin/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;

    AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** Every recorded administrator action, newest first. */
    @GetMapping
    public List<AuditLogEntryResponse> list() {
        return auditLogService.listAll();
    }
}
