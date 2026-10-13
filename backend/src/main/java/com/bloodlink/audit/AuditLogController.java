package com.bloodlink.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.audit.dto.AuditLogPageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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

    /**
     * One page of recorded administrator actions, newest first.
     *
     * {@code page} is 0-based and defaults to 0; {@code size} defaults to 20 and
     * is capped at 100, so a caller cannot ask for the whole, ever-growing log in
     * one response the way the original version of this endpoint did. Both are
     * validated directly on the {@code @RequestParam}s rather than refused inside
     * the service — a bad value (negative page, size out of range, a non-number)
     * is never silently corrected, only refused with a 400 naming the field.
     *
     * Deliberately no {@code @Validated} on this class: Spring MVC has validated
     * constraint-annotated {@code @RequestParam}s natively since 6.1, and that is
     * what {@code GlobalExceptionHandler.handleParameterValidation} expects —
     * see {@code AppealController.reach}'s comment for why {@code @Validated}
     * would route the same constraints through the wrong exception type.
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
